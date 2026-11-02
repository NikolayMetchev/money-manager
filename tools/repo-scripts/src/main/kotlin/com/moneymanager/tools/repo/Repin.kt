package com.moneymanager.tools.repo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLEncoder
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

private const val REPIN_USAGE = "usage: repin node|jdk|bytecode|skiko|pdfium|aapt2 <args>"

/** Re-pins what a version bump in MODULE.bazel leaves stale; `bazel/renovate/repin.sh` runs each subcommand. */
fun repin(args: List<String>) {
    val rest = args.drop(1)
    when (args.firstOrNull()) {
        "node" -> repinNode(workspaceFile(rest[0]))
        "jdk" -> repinJdk(workspaceFile(rest[0]), workspaceFile(rest[1]), workspaceFile(rest[2]))
        "bytecode" -> repinBytecode(File(rest[0]), workspaceFile("."))
        "skiko" -> repinSkiko(workspaceFile(rest[0]))
        "pdfium" -> repinPdfium(workspaceFile(rest[0]))
        "aapt2" -> repinAapt2(workspaceFile(rest[0]), workspaceFile(rest[1]))
        else -> fail(REPIN_USAGE)
    }
}

private fun constant(
    text: String,
    name: String,
): String =
    Regex("""^$name = "([^"]+)"""", MULTILINE).find(text)?.groupValues?.get(1)
        ?: fail("no $name constant")

// --- Node.js: the SHA-256 of each archive node.toolchain lists, from nodejs.org's SHASUMS256.txt.

private val NODE_ENTRY =
    Regex("""(\["node-v" \+ NODE \+ "([^"]+)", "node-v" \+ NODE \+ "[^"]+", ")([0-9a-f]{64})("])""")

fun repinNode(
    file: File,
    sums: (String) -> String = { String(httpGet("https://nodejs.org/dist/v$it/SHASUMS256.txt")) },
) {
    val module = file.readText()
    val version = constant(module, "NODE")
    val byName =
        sums(version).lines().filter { it.isNotBlank() }.associate {
            val (sha, name) = it.trim().split(Regex("\\s+"))
            name to sha
        }
    val (updated, count) =
        NODE_ENTRY.replaceCounting(module) { m ->
            val archive = "node-v$version${m.groupValues[2]}"
            val sha = byName[archive] ?: fail("$archive isn't in Node $version's SHASUMS256.txt")
            m.groupValues[1] + sha + m.groupValues[4]
        }
    if (count == 0) fail("no Node.js archives found in $file")
    if (writeIfChanged(file, module, updated)) println("Re-pinned $count Node.js $version archives")
}

// --- The JDK: each platform's newest Azul Zulu build of MODULE.bazel's JDK, with its SHA-256 from Azul's metadata API.

private const val AZUL = "https://api.azul.com/metadata/v1/zulu/packages/"

/** JDK_ARCHIVES' keys (Bazel's os_cpu) to Azul's os, arch and archive type. */
private val JDK_PLATFORMS =
    mapOf(
        "linux_aarch64" to Triple("linux", "aarch64", "tar.gz"),
        "linux_x86_64" to Triple("linux", "x64", "tar.gz"),
        "macos_aarch64" to Triple("macos", "aarch64", "tar.gz"),
        "macos_x86_64" to Triple("macos", "x64", "tar.gz"),
        "windows_x86_64" to Triple("windows", "x64", "zip"),
    )

private fun fetchJson(url: String) = Json.parseToJsonElement(String(httpGet(url)))

private fun JsonArray.ints() = map { it.jsonPrimitive.int }

private val intListOrder =
    Comparator<List<Int>> { a, b ->
        a.zip(b).map { (x, y) -> x.compareTo(y) }.firstOrNull { it != 0 } ?: a.size.compareTo(b.size)
    }

private fun azulArchive(
    version: String,
    platform: Triple<String, String, String>,
): Pair<String, String> {
    val (os, arch, type) = platform
    val query =
        mapOf(
            "java_version" to version,
            "os" to os,
            "arch" to arch,
            "archive_type" to type,
            "java_package_type" to "jdk",
            "javafx_bundled" to "false",
            "crac_supported" to "false",
            "release_status" to "ga",
            "availability_types" to "CA",
            "page_size" to "100",
        ).entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
    val packages =
        fetchJson("$AZUL?$query").jsonArray.map { it.jsonObject }.filter {
            "musl" !in it.getValue("name").jsonPrimitive.content &&
                it
                    .getValue("java_version")
                    .jsonArray
                    .ints()
                    .take(3)
                    .joinToString(".") == version
        }
    if (packages.isEmpty()) fail("Azul publishes no Zulu $version JDK for $os $arch ($type)")
    val newest =
        packages.maxWith(
            compareBy<JsonObject, List<Int>>(intListOrder) { it.getValue("java_version").jsonArray.ints() }
                .thenBy(intListOrder) { it.getValue("distro_version").jsonArray.ints() },
        )
    val sha = fetchJson(AZUL + newest.getValue("package_uuid").jsonPrimitive.content).jsonObject.getValue("sha256_hash")
    return newest.getValue("name").jsonPrimitive.content to sha.jsonPrimitive.content
}

private val JDK_ENTRY = Regex("""^( {4}"([a-z0-9_]+)": \[)"([^"]+)", "([0-9a-f]{64})"(],)$""", MULTILINE)

fun repinJdk(
    moduleFile: File,
    bazelrcFile: File,
    toolVersionsFile: File,
    archive: (String, Triple<String, String, String>) -> Pair<String, String> = ::azulArchive,
) {
    val module = moduleFile.readText()
    val version = constant(module, "JDK")
    // A pin already for this release (Zulu names it -jdk<version>.<build> or -jdk<version>-<os>) is kept, so repin.sh,
    // which runs for every MODULE.bazel change Renovate makes, only asks Azul when JDK itself moved.
    val current = Regex("-jdk${Regex.escape(version)}[.-]")
    val (updated, count) =
        JDK_ENTRY.replaceCounting(module) { m ->
            val platform = JDK_PLATFORMS[m.groupValues[2]] ?: fail("JDK_ARCHIVES has an unknown platform ${m.groupValues[2]}")
            var (name, sha) = m.groupValues[3] to m.groupValues[4]
            if (!current.containsMatchIn(name)) {
                val pinned = archive(version, platform)
                name = pinned.first
                sha = pinned.second
            }
            """${m.groupValues[1]}"$name", "$sha"${m.groupValues[5]}"""
        }
    if (count != JDK_PLATFORMS.size) fail("found $count JDK_ARCHIVES entries in $moduleFile, expected ${JDK_PLATFORMS.size}")
    if (writeIfChanged(moduleFile, module, updated)) println("Re-pinned the Zulu $version archives")

    val bazelrc = bazelrcFile.readText()
    val feature = version.substringBefore(".")
    val newRc = Regex("""(java_runtime_version=remotejdk_)\d+""").replace(bazelrc) { it.groupValues[1] + feature }
    if (writeIfChanged(bazelrcFile, bazelrc, newRc)) println("Set .bazelrc's runtime to JDK $feature")

    // asdf-java names a Zulu build by Azul's own version (zulu27.30.109-ca-jdk27.0.1… is zulu-27.30.109).
    val zulu =
        Regex(""""linux_x86_64": \["zulu([0-9.]+)-""").find(updated)?.groupValues?.get(1)
            ?: fail("no linux_x86_64 Zulu archive in $moduleFile")
    val toolVersions = toolVersionsFile.readText()
    val javaLine = Regex("""^(java[ \t]+)\S+([ \t]*(?:#.*)?)$""", MULTILINE)
    val (newTools, lines) = javaLine.replaceCounting(toolVersions) { "${it.groupValues[1]}zulu-$zulu${it.groupValues[2]}" }
    if (lines != 1) fail("$toolVersionsFile must have exactly one java line, found $lines")
    if (writeIfChanged(toolVersionsFile, toolVersions, newTools)) println(".tool-versions now names Zulu $zulu")
}

// --- Bytecode: MODULE.bazel's JDK, or the newest JVM target the Kotlin compiler has if that is older.

private val BYTECODE_FILES =
    mapOf(
        ".bazelrc" to listOf("""(java_language_version=)\d+"""),
        "bazel/kmp/BUILD.bazel" to listOf("""(jvm_target = ")\d+"""),
        "bazel/kmp/java/BUILD.bazel" to listOf("""(source_version = ")\d+""", """(target_version = ")\d+"""),
    )

fun repinBytecode(
    kotlinc: File,
    root: File,
) {
    val module = File(root, "MODULE.bazel").readText()
    val jdk =
        Regex("""^JDK = "(\d+)[^"]*"""", MULTILINE)
            .find(module)
            ?.groupValues
            ?.get(1)
            ?.toInt() ?: fail("no JDK constant")
    val kotlin = constant(module, "KOTLIN")
    val compilerVersion = File(kotlinc, "build.txt").readText().trim()
    if (compilerVersion != kotlin) fail("rules_kotlin's compiler is $compilerVersion, MODULE.bazel's KOTLIN is $kotlin")

    // JvmTarget's constants are named after the targets: JVM_1_8, JVM_9, …, JVM_26.
    val enum =
        ZipFile(File(kotlinc, "lib/kotlin-compiler.jar")).use { zip ->
            zip.getInputStream(zip.getEntry("org/jetbrains/kotlin/config/JvmTarget.class")).readBytes()
        }
    val kotlinNewest =
        Regex("""JVM_(\d+)(?![\d_])""").findAll(String(enum, Charsets.ISO_8859_1)).maxOf { it.groupValues[1].toInt() }
    val target = minOf(jdk, kotlinNewest).toString()

    var changed = false
    for ((path, patterns) in BYTECODE_FILES) {
        val file = File(root, path)
        val text = file.readText()
        var updated = text
        for (pattern in patterns) {
            val (next, count) = Regex(pattern).replaceCounting(updated) { it.groupValues[1] + target }
            if (count == 0) fail("$path has no $pattern")
            updated = next
        }
        changed = writeIfChanged(file, text, updated) || changed
    }
    if (changed) println("Compiling for Java $target (JDK $jdk, Kotlin $kotlin targets up to $kotlinNewest)")
}

// --- Skiko: the release Compose was built against, read from Compose's ui-graphics Gradle module.

fun repinSkiko(
    file: File,
    gradleModule: (String) -> String = {
        String(httpGet("https://repo1.maven.org/maven2/org/jetbrains/compose/ui/ui-graphics/$it/ui-graphics-$it.module"))
    },
) {
    val module = file.readText()
    val compose = constant(module, "COMPOSE_PLUGIN")
    val skiko =
        Json
            .parseToJsonElement(gradleModule(compose))
            .jsonObject
            .getValue("variants")
            .jsonArray
            .asSequence()
            .flatMap { it.jsonObject["dependencies"]?.jsonArray ?: JsonArray(emptyList()) }
            .map { it.jsonObject }
            .filter {
                it.getValue("group").jsonPrimitive.content == "org.jetbrains.skiko" &&
                    it.getValue("module").jsonPrimitive.content == "skiko"
            }.map {
                it
                    .getValue("version")
                    .jsonObject
                    .getValue("requires")
                    .jsonPrimitive.content
            }.toSet()
    if (skiko.size != 1) fail("Compose $compose's ui-graphics names Skiko ${skiko.sorted()}, want one release")
    val version = skiko.single()
    val (updated, count) =
        Regex("""^(SKIKO = ")[^"]+(")""", MULTILINE).replaceCounting(module) {
            it.groupValues[1] + version +
                it.groupValues[2]
        }
    if (count != 1) fail("no SKIKO constant in $file")
    if (writeIfChanged(file, module, updated)) println("Set Skiko to $version, Compose $compose's")
}

// --- pdfium: the SHA-256 of each archive MODULE.bazel downloads for its PDFIUM build (no checksum file is published).

private val PDFIUM_ENTRY =
    Regex(
        """(sha256 = ")([0-9a-f]{64})(",\n {4}url = "https://github\.com/bblanchon/pdfium-binaries/[^"]+" \+ PDFIUM \+ "/(pdfium-[^"]+\.tgz)")""",
    )

fun repinPdfium(
    file: File,
    download: (String) -> ByteArray = ::httpGet,
) {
    val module = file.readText()
    val build = constant(module, "PDFIUM")
    val (updated, count) =
        PDFIUM_ENTRY.replaceCounting(module) { m ->
            val url = "https://github.com/bblanchon/pdfium-binaries/releases/download/chromium%2F$build/${m.groupValues[4]}"
            m.groupValues[1] + sha256Hex(download(url)) + m.groupValues[3]
        }
    if (count == 0) fail("no pdfium archives found in $file")
    if (writeIfChanged(file, module, updated)) println("Re-pinned $count pdfium $build archives")
}

// --- aapt2: the build AGP names in its plugin jar, and each host's jar SHA-256.

private const val MAVEN = "https://dl.google.com/android/maven2/com/android/tools/build"

fun repinAapt2(
    moduleFile: File,
    pinsFile: File,
    download: (String) -> ByteArray = ::httpGet,
) {
    val agp =
        Regex("""^android_sdk\.agp\(version = "([^"]+)"\)""", MULTILINE).find(moduleFile.readText())?.groupValues?.get(1)
            ?: fail("no android_sdk.agp in $moduleFile")
    val pins = pinsFile.readText()
    val properties =
        ZipInputStream(ByteArrayInputStream(download("$MAVEN/gradle/$agp/gradle-$agp.jar"))).use { zip ->
            generateSequence { zip.nextEntry }.first { it.name == "com/android/build/gradle/internal/res/aapt2_version.properties" }
            String(zip.readBytes())
        }
    val build = Regex("""^aapt2Version=(\S+)""", MULTILINE).find(properties)?.groupValues?.get(1) ?: fail("no aapt2Version")
    val withBuild = Regex("""^AAPT2_BUILD = "[^"]*"""", MULTILINE).replace(pins) { """AAPT2_BUILD = "$build"""" }
    val version = "$agp-$build"
    val (updated, count) =
        Regex("""( {4}"(linux|osx|windows)": ")[0-9a-f]{64}(",)""").replaceCounting(withBuild) { m ->
            m.groupValues[1] + sha256Hex(download("$MAVEN/aapt2/$version/aapt2-$version-${m.groupValues[2]}.jar")) + m.groupValues[3]
        }
    if (count != 3) fail("expected 3 aapt2 checksums in $pinsFile, found $count")
    if (writeIfChanged(pinsFile, pins, updated)) println("Re-pinned aapt2 $version")
}
