package com.moneymanager.tools.repo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

private const val REPIN_USAGE = "usage: repin node|jdk|bytecode|skiko|pdfium|aapt2|android-sdk <args>"

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
        "android-sdk" -> repinAndroidSdk(workspaceFile(rest[0]), workspaceFile(rest[1]), workspaceFile(rest[2]))
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

private val apiJson = Json { ignoreUnknownKeys = true }

/** The fields of Azul's package listing that choose the newest JDK. */
@Serializable
private class AzulPackage(
    val name: String,
    @SerialName("java_version") val javaVersion: List<Int>,
    @SerialName("distro_version") val distroVersion: List<Int>,
    @SerialName("package_uuid") val packageUuid: String,
)

@Serializable
private class AzulPackageDetail(
    @SerialName("sha256_hash") val sha256: String,
)

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
        apiJson.decodeFromString<List<AzulPackage>>(String(httpGet("$AZUL?$query"))).filter {
            "musl" !in it.name && it.javaVersion.take(3).joinToString(".") == version
        }
    if (packages.isEmpty()) fail("Azul publishes no Zulu $version JDK for $os $arch ($type)")
    val newest =
        packages.maxWith(
            compareBy<AzulPackage, List<Int>>(intListOrder) { it.javaVersion }.thenBy(intListOrder) { it.distroVersion },
        )
    val sha = apiJson.decodeFromString<AzulPackageDetail>(String(httpGet(AZUL + newest.packageUuid))).sha256
    return newest.name to sha
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

/** The slice of a Gradle module file that names a dependency's required version. */
@Serializable
private class GradleModule(
    val variants: List<GradleVariant>,
)

@Serializable
private class GradleVariant(
    val dependencies: List<GradleDependency> = emptyList(),
)

@Serializable
private class GradleDependency(
    val group: String,
    val module: String,
    val version: GradleVersion,
)

@Serializable
private class GradleVersion(
    val requires: String? = null,
)

fun repinSkiko(
    file: File,
    gradleModule: (String) -> String = {
        String(httpGet("https://repo1.maven.org/maven2/org/jetbrains/compose/ui/ui-graphics/$it/ui-graphics-$it.module"))
    },
) {
    val module = file.readText()
    val compose = constant(module, "COMPOSE_PLUGIN")
    val skiko =
        apiJson
            .decodeFromString<GradleModule>(gradleModule(compose))
            .variants
            .flatMap { it.dependencies }
            .filter { it.group == "org.jetbrains.skiko" && it.module == "skiko" }
            .map { it.version.requires ?: fail("Compose $compose's skiko dependency requires no version") }
            .toSet()
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

// --- The Android SDK: the compile_sdk platform and platform-tools from Google's index, and the license they're under.

private const val SDK_REPOSITORY = "https://dl.google.com/android/repository"
private const val STABLE_CHANNEL = "channel-0"

/** The index's host names against pins.bzl's (aapt2's, which are AGP's). */
private val SDK_HOSTS = mapOf("linux" to "linux", "macosx" to "osx", "windows" to "windows")

private val PLATFORM_TAG = Regex("""(android_sdk\.platform\(version = ")([^"]+)("\))""")
private val TOOLS_TAG = Regex("""android_sdk\.platform_tools\(version = "([^"]+)"\)""")
private val PLATFORM_PIN = Regex("""^ANDROID_PLATFORM = \{\n(.*?)^}""", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))
private val TOOLS_PIN = Regex("""^PLATFORM_TOOLS = \{\n(.*?)^}""", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))
private val PIN_FIELD = Regex(""""(\w+)": (?:"([^"]*)"|(\d+))""")
private val TOOLS_ENTRY = Regex(""""(\w+)": \["([^"]+)", "([0-9a-f]{64})"]""")

private class SdkArchive(
    val file: String,
    val sha1: String,
)

fun repinAndroidSdk(
    moduleFile: File,
    pinsFile: File,
    licenseFile: File,
    download: (String) -> ByteArray = ::httpGet,
) {
    val module = moduleFile.readText()
    val level =
        Regex("""^ *compile_sdk = (\d+),$""", MULTILINE).find(module)?.groupValues?.get(1)
            ?: fail("no compile_sdk in $moduleFile")
    val platformRelease = PLATFORM_TAG.find(module)?.groupValues?.get(2) ?: fail("no android_sdk.platform in $moduleFile")
    val toolsVersion = TOOLS_TAG.find(module)?.groupValues?.get(1) ?: fail("no android_sdk.platform_tools in $moduleFile")
    val index =
        DocumentBuilderFactory
            .newInstance()
            .apply { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(download("$SDK_REPOSITORY/repository2-3.xml")))
            .documentElement
    val pins = pinsFile.readText()

    // Only a new archive is downloaded: repin.sh runs on every Renovate branch, and the platform is 67 MB.
    val fetch = { archive: SdkArchive ->
        val bytes = download("$SDK_REPOSITORY/${archive.file}")
        val sha1 = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
        check(sha1 == archive.sha1) { "${archive.file}: the index says SHA-1 ${archive.sha1}, the download is $sha1" }
        bytes
    }

    // A release of another API means compile_sdk was changed by hand, and compile_sdk decides.
    val platform =
        if (platformRelease.substringBefore('_').substringBefore('.') == level) {
            index.stableArchives("platforms;", "platform-$platformRelease.zip")?.values?.single()
                ?: error("android_sdk.platform $platformRelease isn't a stable release in the SDK index")
        } else {
            listOf("platforms;android-$level.0", "platforms;android-$level")
                .firstNotNullOfOrNull { index.stablePackage(it) }
                ?.archives()
                ?.values
                ?.single()
                ?: error("no stable Android platform for API $level in the SDK index")
        }
    val pinnedPlatform =
        PLATFORM_PIN.find(pins)?.let { pin ->
            PIN_FIELD.findAll(pin.groupValues[1]).associate { it.groupValues[1] to it.groupValues[2].ifEmpty { it.groupValues[3] } }
        } ?: fail("no ANDROID_PLATFORM in $pinsFile")
    val platformFields =
        if (pinnedPlatform["file"] == platform.file && pinnedPlatform["compile_sdk"] == level) {
            pinnedPlatform
        } else {
            val bytes = fetch(platform)
            val dir =
                ZipInputStream(ByteArrayInputStream(bytes)).use {
                    (it.nextEntry ?: error("${platform.file} is empty")).name.substringBefore('/')
                }
            mapOf("dir" to dir, "file" to platform.file, "sha256" to sha256Hex(bytes))
        }

    val tools =
        index.stableArchives("platform-tools", "platform-tools_r$toolsVersion-linux.zip")
            ?: error("android_sdk.platform_tools $toolsVersion isn't a stable release in the SDK index")
    val pinnedTools =
        TOOLS_ENTRY.findAll(TOOLS_PIN.find(pins)?.groupValues?.get(1) ?: fail("no PLATFORM_TOOLS in $pinsFile")).associate {
            it.groupValues[1] to (it.groupValues[2] to it.groupValues[3])
        }
    val toolsEntries =
        SDK_HOSTS.values.sorted().joinToString("") { host ->
            val archive = tools[host] ?: error("platform-tools has no archive for $host")
            val sha256 =
                pinnedTools[host]?.takeIf { it.first == archive.file }?.second ?: sha256Hex(fetch(archive))
            "    \"$host\": [\"${archive.file}\", \"$sha256\"],\n"
        }

    val platformEntries =
        "    \"compile_sdk\": $level,\n" +
            listOf("dir", "file", "sha256").joinToString("") { "    \"$it\": \"${platformFields[it]}\",\n" }
    val updated =
        TOOLS_PIN.replace(PLATFORM_PIN.replace(pins) { "ANDROID_PLATFORM = {\n$platformEntries}" }) {
            "PLATFORM_TOOLS = {\n$toolsEntries}"
        }
    if (writeIfChanged(pinsFile, pins, updated)) println("Re-pinned the Android SDK: ${platform.file}, ${tools.values.first().file}")

    val release = platform.file.removePrefix("platform-").removeSuffix(".zip")
    writeIfChanged(moduleFile, module, PLATFORM_TAG.replace(module) { "${it.groupValues[1]}$release${it.groupValues[3]}" })

    val license =
        index
            .elements("license")
            .firstOrNull { it.getAttribute("id") == "android-sdk-license" }
            ?.textContent
            ?: error("no android-sdk-license in the SDK index")
    if (writeIfChanged(licenseFile, licenseFile.readText(), license)) {
        println("The Android SDK License changed: everyone accepts it again")
    }
}

private fun Element.elements(tag: String): List<Element> =
    getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

private fun Element.stablePackage(path: String): Element? =
    elements("remotePackage").firstOrNull { it.getAttribute("path") == path && it.isStable() }

private fun Element.isStable(): Boolean = elements("channelRef").single().getAttribute("ref") == STABLE_CHANNEL

/** The archives of the stable package under [pathPrefix] that holds [file]. */
private fun Element.stableArchives(
    pathPrefix: String,
    file: String,
): Map<String, SdkArchive>? =
    elements("remotePackage")
        .asSequence()
        .filter { it.getAttribute("path").startsWith(pathPrefix) && it.isStable() }
        .map { it.archives() }
        .firstOrNull { archives -> archives.values.any { it.file == file } }

/** A package's archives by pins.bzl's host name; an archive for every host (a platform's) is under one key. */
private fun Element.archives(): Map<String, SdkArchive> =
    elements("archive").associate { archive ->
        val host = archive.elements("host-os").singleOrNull()?.textContent
        val complete = archive.elements("complete").single()
        (SDK_HOSTS[host] ?: "any") to
            SdkArchive(complete.elements("url").single().textContent, complete.elements("checksum").single().textContent)
    }
