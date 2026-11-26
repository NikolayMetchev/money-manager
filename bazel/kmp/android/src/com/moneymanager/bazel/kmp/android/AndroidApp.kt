package com.moneymanager.bazel.kmp.android

import com.android.build.gradle.ProguardFiles
import com.android.build.gradle.internal.tasks.manifest.ManifestProviderImpl
import com.android.build.gradle.internal.tasks.manifest.mergeManifests
import com.android.builder.dexing.KeepRuleFile
import com.android.builder.dexing.MainDexListConfig
import com.android.builder.dexing.ProguardConfig
import com.android.builder.dexing.ProguardOutputFiles
import com.android.builder.dexing.R8OutputType
import com.android.builder.dexing.ResourceShrinkingConfig
import com.android.builder.dexing.ToolConfig
import com.android.builder.dexing.runR8
import com.android.builder.internal.InstrumentedTestManifestGenerator
import com.android.builder.internal.packaging.ApkFlinger
import com.android.builder.packaging.DexFileComparator
import com.android.builder.packaging.PackagingUtils
import com.android.builder.symbols.exportToCompiledJava
import com.android.ide.common.blame.Message
import com.android.ide.common.blame.MessageReceiver
import com.android.ide.common.signing.KeystoreHelper
import com.android.ide.common.symbols.SymbolIo
import com.android.ide.common.symbols.SymbolTable
import com.android.manifmerger.ManifestMerger2
import com.android.manifmerger.ManifestProvider
import com.android.manifmerger.ManifestSystemProperty
import com.android.manifmerger.MergingReport
import com.android.manifmerger.PlaceholderHandler
import com.android.tools.build.apkzlib.sign.SigningOptions
import com.android.tools.build.apkzlib.zfile.ApkCreatorFactory
import com.android.tools.profgen.ArtProfile
import com.android.tools.profgen.ArtProfileSerializer
import com.android.tools.profgen.DexFile
import com.android.tools.profgen.Diagnostics
import com.android.tools.profgen.HumanReadableProfile
import com.android.tools.profgen.ObfuscationMap
import com.android.tools.profgen.expandWildcards
import com.android.tools.profgen.parseDexFiles
import com.android.tools.r8.Version
import com.android.utils.StdLogger
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.time.LocalDateTime
import java.util.concurrent.Executors
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText

/*
 * The steps of AGP's APK build that aren't a tool of their own (aapt2, D8, R8), done with AGP's own classes:
 * `manifest` is `processDebugManifest`, `test-manifest` a test APK's `processAndroidDeviceTestManifest`, `rjar` the
 * R.jar `processDebugResources` writes, `default-rules` AGP's default optimizing rules file, `embedded-rules` the keep
 * rules AGP extracts from library jars, `debug-keystore` the key AGP makes when ~/.android has none, and `package`
 * `packageDebug`: the APK through AGP's `ApkFlinger`, with AGP's default Java-resource excludes and merges, its
 * no-compress rules and 16 KB page alignment, v2-signed. A release build adds `java-res`, `art-profile`, `r8`,
 * `compile-art-profile` and `vcs-info`: the release tasks of the same names.
 */

private typealias Flags = Map<String, List<String>>

/** The DOS epoch zip tools use for reproducible entries. */
private val ZIP_EPOCH: LocalDateTime = LocalDateTime.of(1980, 2, 1, 0, 0)

private val R8_DIR = Regex("META-INF/com\\.android\\.tools/r8(?:-from-([0-9.]+))?(?:-upto-([0-9.]+))?/[^/]+")

/** PackagingOptionsUtils' defaultExcludes, plus the kotlin_module files an APK drops (ResourcesApkPackagingImpl). */
private val EXCLUDES =
    listOf(
        "/META-INF/LICENSE",
        "/META-INF/LICENSE.txt",
        "/META-INF/MANIFEST.MF",
        "/META-INF/**/MANIFEST.MF",
        "/META-INF/NOTICE",
        "/META-INF/NOTICE.txt",
        "/META-INF/*.DSA",
        "/META-INF/*.EC",
        "/META-INF/*.SF",
        "/META-INF/*.RSA",
        "/META-INF/maven/**",
        "/META-INF/proguard/*",
        "/META-INF/com.android.tools/**",
        "/NOTICE",
        "/NOTICE.txt",
        "/LICENSE.txt",
        "/LICENSE",
        "**/.svn/**",
        "**/CVS/**",
        "**/SCCS/**",
        "**/.*/**",
        "**/.*",
        "**/*~",
        "**/thumbs.db",
        "**/picasa.ini",
        "**/about.html",
        "**/package.html",
        "**/overview.html",
        "**/protobuf.meta",
        "**/_*",
        "**/_*/**",
        "**/*.kotlin_metadata",
        "/META-INF/*.kotlin_module",
    ).map(::glob)

/** defaultMerges. */
private val MERGES = listOf("/META-INF/services/**", "jacoco-agent.properties").map(::glob)

/** A baseline profile that doesn't parse fails the step. */
private val FAIL_ON_PROFILE_ERROR =
    object : Diagnostics {
        override fun onError(error: String) = throw IllegalStateException("Error parsing baseline-prof.txt : $error")
    }

fun main(args: Array<String>) {
    val flags = parse(args.drop(1))
    when (args[0]) {
        "manifest" -> manifest(flags)
        "test-manifest" -> testManifest(flags)
        "rjar" -> rJar(flags)
        "default-rules" -> ProguardFiles.createProguardFile(flags.one("name"), File(flags.one("out")))
        "embedded-rules" -> embeddedRules(flags)
        "java-res" -> javaRes(flags)
        "art-profile" -> artProfile(flags)
        "r8" -> r8(flags)
        "compile-art-profile" -> compileArtProfile(flags)
        "vcs-info" -> vcsInfo(flags)
        "package" -> packageApk(flags)
        "debug-keystore" ->
            KeystoreHelper.createDebugStore(
                null,
                File(flags.one("out")),
                "android",
                "android",
                "androiddebugkey",
                StdLogger(StdLogger.Level.WARNING),
            )
        else -> throw IllegalArgumentException("unknown command ${args[0]}")
    }
}

/** `--flag value` pairs; a repeated flag collects its values in order. */
private fun parse(args: List<String>): Flags {
    val out = linkedMapOf<String, MutableList<String>>()
    var i = 0
    while (i < args.size) {
        require(args[i].startsWith("--")) { "expected a flag, got ${args[i]}" }
        val name = args[i].substring(2)
        val bool = i + 1 == args.size || args[i + 1].startsWith("--")
        out.getOrPut(name) { mutableListOf() } += if (bool) "true" else args[++i]
        i++
    }
    return out
}

private fun Flags.one(name: String): String {
    val values = this[name]
    require(values != null && values.size == 1) { "expected one --$name" }
    return values.single()
}

/** An app whose libraries ship no baseline profile has none to rewrite. */
private fun Flags.optionalPath(name: String): Path? = if (name in this) Path.of(one(name)) else null

private fun Flags.all(name: String): List<String> = this[name] ?: emptyList()

private fun Flags.manifestProviders(): List<ManifestProvider> = all("dep").map { ManifestProviderImpl(File(it), it) }

/** ProcessApplicationManifest: the app's manifest merged with its libraries', as AGP passes them. */
private fun manifest(flags: Flags) {
    val features = mutableListOf<ManifestMerger2.Invoker.Feature>()
    if ("debuggable" in flags) {
        features += ManifestMerger2.Invoker.Feature.DEBUGGABLE
    }
    features += ManifestMerger2.Invoker.Feature.VALIDATE_EXTRACT_NATIVE_LIBS_FROM_DEPENDENCIES
    val report =
        mergeManifests(
            File(flags.one("main")),
            listOf(),
            flags.manifestProviders(),
            listOf(),
            null,
            flags.one("application-id"),
            flags.one("namespace"),
            false,
            flags.one("version-code").toInt(),
            flags.one("version-name"),
            flags.one("min-sdk"),
            flags.one("target-sdk"),
            null,
            false,
            // useLegacyPackaging unset and minSdk 23 or newer: AGP keeps native libraries uncompressed in the APK.
            false,
            flags.one("out"),
            null,
            ManifestMerger2.MergeType.APPLICATION,
            // manifestPlaceholders: the app sets none (mergeManifests adds applicationId itself).
            mapOf(),
            features,
            listOf(),
            null,
            null,
            StdLogger(StdLogger.Level.WARNING),
            true,
            false,
            flags.one("compile-sdk").toInt(),
            false,
        )
    if (report.result.isError) {
        report.loggingRecords.forEach(System.err::println)
        error("manifest merge failed")
    }
}

/**
 * ProcessTestManifest for a library's self-instrumenting test APK: the instrumentation AGP generates, merged under the
 * test source set's manifest (when it has one), then with the libraries'.
 */
private fun testManifest(flags: Flags) {
    val applicationId = flags.one("application-id")
    val minSdk = flags.one("min-sdk")
    val targetSdk = flags.one("target-sdk")
    val runner = flags.one("runner")
    val logger = StdLogger(StdLogger.Level.WARNING)
    val tmp = Files.createTempDirectory("test-manifest")
    var generated = tmp.resolve("generated.xml").toFile()
    InstrumentedTestManifestGenerator(generated, applicationId, minSdk, targetSdk, applicationId, runner, false, false)
        .generate()
    if ("test-manifest" in flags) {
        val intermediate =
            ManifestMerger2
                .newMerger(File(flags.one("test-manifest")), logger, ManifestMerger2.MergeType.APPLICATION)
                .addLibraryManifest(generated)
                .addAllowedNonUniqueNamespace(applicationId)
                .setOverride(ManifestSystemProperty.Document.PACKAGE, applicationId)
                .setOverride(ManifestSystemProperty.UsesSdk.MIN_SDK_VERSION, minSdk)
                .setOverride(ManifestSystemProperty.Instrumentation.TARGET_PACKAGE, applicationId)
                .setNamespace(applicationId)
                .withFeatures(
                    ManifestMerger2.Invoker.Feature.DISABLE_MINSDKLIBRARY_CHECK,
                    ManifestMerger2.Invoker.Feature.CHECK_IF_PACKAGE_IN_MAIN_MANIFEST,
                    ManifestMerger2.Invoker.Feature.USES_SDK_IN_MANIFEST_LENIENT_HANDLING,
                    ManifestMerger2.Invoker.Feature.DISABLE_REPLACE_WARNING,
                )
        intermediate.setPlaceHolderValue(PlaceholderHandler.INSTRUMENTATION_RUNNER, runner)
        intermediate.setOverride(ManifestSystemProperty.Instrumentation.NAME, runner)
        intermediate.setOverride(ManifestSystemProperty.Instrumentation.FUNCTIONAL_TEST, "false")
        intermediate.setOverride(ManifestSystemProperty.Instrumentation.HANDLE_PROFILING, "false")
        intermediate.setOverride(ManifestSystemProperty.UsesSdk.TARGET_SDK_VERSION, targetSdk)
        val merged = tmp.resolve("intermediate.xml").toFile()
        writeMerged(intermediate.merge(), merged)
        generated = merged
    }
    val features =
        mutableListOf(
            ManifestMerger2.Invoker.Feature.DISABLE_MINSDKLIBRARY_CHECK,
            ManifestMerger2.Invoker.Feature.REMOVE_TOOLS_DECLARATIONS,
            ManifestMerger2.Invoker.Feature.USES_SDK_IN_MANIFEST_LENIENT_HANDLING,
        )
    // A library's test APK is debug; a com.android.test module's release variant isn't.
    if ("debuggable" in flags) {
        features += ManifestMerger2.Invoker.Feature.DEBUGGABLE
    }
    val last =
        ManifestMerger2
            .newMerger(generated, logger, ManifestMerger2.MergeType.APPLICATION)
            .withFeatures(*features.toTypedArray())
            .setOverride(ManifestSystemProperty.Document.PACKAGE, applicationId)
            .addManifestProviders(flags.manifestProviders())
            .setNamespace(applicationId)
    // As for the app: minSdk 23 or newer without useLegacyPackaging keeps native libraries uncompressed.
    last.setOverride(ManifestSystemProperty.Application.EXTRACT_NATIVE_LIBS, "false")
    writeMerged(last.merge(), File(flags.one("out")))
}

private fun writeMerged(
    report: MergingReport,
    out: File,
) {
    if (report.result.isError) {
        report.loggingRecords.forEach(System.err::println)
        error("manifest merge failed")
    }
    val merged =
        report.getMergedDocument(MergingReport.MergedManifestKind.MERGED)
            ?: error("manifest merge produced no merged document")
    out.toPath().writeText(merged)
}

/**
 * The app's R.jar: with non-transitive R classes each library's R class holds the symbols its own R.txt lists, with
 * the app's final ids from aapt2's R.txt. `--lib` is `<AndroidManifest.xml>:<R.txt>`, the R.txt empty when the AAR has
 * none.
 */
private fun rJar(flags: Flags) {
    val namespace = flags.one("namespace")
    val app = SymbolIo.readFromAapt(File(flags.one("r-txt")), namespace)
    val tables = mutableListOf(SymbolTable.builder().tablePackage(namespace).build())
    for (lib in flags.all("lib")) {
        val (manifest, rTxtPath) = lib.split(":", limit = 2)
        val pkg = packageOf(Path.of(manifest))
        val rTxt = File(rTxtPath)
        val own =
            if (rTxt.length() == 0L) {
                SymbolTable.builder().tablePackage(pkg).build()
            } else {
                SymbolIo.readFromAaptNoValues(rTxt, pkg)
            }
        tables += app.filter(own).rename(pkg)
    }
    // Non-final, as android.nonFinalResIds (on by default) makes them: optimized resource shrinking traces them.
    exportToCompiledJava(tables, Path.of(flags.one("out")), false, null, null)
}

private fun packageOf(manifest: Path): String =
    DocumentBuilderFactory
        .newInstance()
        .newDocumentBuilder()
        .parse(manifest.toFile())
        .documentElement
        .getAttribute("package")

/**
 * The keep rules library jars carry, as AGP extracts them for R8: a jar's `META-INF/com.android.tools/r8*` rules when
 * it has any (the directory whose version range takes R8's), else its older generic ones, after an AAR's own
 * (`--aar-rules <jar>=<rules file>`): one library at a time, in classpath order, as AGP hands R8 the runtime
 * classpath's rules.
 */
private fun embeddedRules(flags: Flags) {
    val aarRules = flags.all("aar-rules").associate { rule -> rule.split("=", limit = 2).let { it[0] to it[1] } }
    val out = StringBuilder()
    for (jar in flags.all("jar")) {
        aarRules[jar]?.let { rules ->
            out.append("# ").append(rules).append('\n')
            out.append(Path.of(rules).readText()).append('\n')
        }
        ZipFile(jar).use { zip ->
            val entries = zip.stream().toList().filter { !it.isDirectory }.sortedBy { it.name }
            val r8 = entries.filter { it.name.startsWith("META-INF/com.android.tools/") && r8Applies(it.name) }
            val generic = entries.filter { it.name.startsWith("META-INF/proguard/") }
            val hasR8Dir = zip.stream().anyMatch { it.name.startsWith("META-INF/com.android.tools/r8") }
            for (entry in if (hasR8Dir) r8 else generic) {
                out.append("# ").append(entry.name).append('\n')
                zip.getInputStream(entry).use { out.append(String(it.readAllBytes(), Charsets.UTF_8)).append('\n') }
            }
        }
    }
    Path.of(flags.one("out")).writeText(out)
}

/** Whether a `r8[-from-<v>][-upto-<v>]` directory's range takes the R8 that AGP bundles. */
private fun r8Applies(name: String): Boolean {
    val match = R8_DIR.matchEntire(name) ?: return false
    val version = Version.getVersionString().split(" ")[0]
    val from = match.groups[1]?.value
    val upto = match.groups[2]?.value
    return (from == null || compare(version, from) >= 0) && (upto == null || compare(version, upto) < 0)
}

private fun compare(
    a: String,
    b: String,
): Int {
    val x = a.split(Regex("[.-]"))
    val y = b.split(Regex("[.-]"))
    for (i in 0 until maxOf(x.size, y.size)) {
        val p = x.getOrNull(i)?.let(::number) ?: 0
        val q = y.getOrNull(i)?.let(::number) ?: 0
        if (p != q) {
            return p.compareTo(q)
        }
    }
    return 0
}

private fun number(part: String): Int = if (part.isNotEmpty() && part.all(Char::isDigit)) part.toInt() else 0

/** AGP's glob: `**` followed by `/` is any number of directories (none too), `*` stays in one. */
private fun glob(glob: String): Regex {
    val re = StringBuilder(if (glob.startsWith("/")) "" else "(?:.*/)?")
    val g = glob.removePrefix("/")
    var i = 0
    while (i < g.length) {
        val c = g[i]
        when {
            g.startsWith("**/", i) -> {
                re.append("(?:.*/)?")
                i += 2
            }
            g.startsWith("**", i) -> {
                re.append(".*")
                i++
            }
            c == '*' -> re.append("[^/]*")
            c == '?' -> re.append("[^/]")
            else -> re.append(Regex.escape(c.toString()))
        }
        i++
    }
    return Regex(re.toString())
}

private fun List<Regex>.matchesAny(path: String): Boolean = any { it.matches(path) }

/**
 * The APK: aapt2's linked resources and manifest, the dex files, the Java resources of the runtime jars (in classpath
 * order: excluded, merged or the first one kept), the AARs' assets and native libraries, any
 * `--file <path in the APK>=<file>` (the ART profile, the VCS info), and AGP's app metadata.
 */
private fun packageApk(flags: Flags) {
    val javaRes = mergeJavaRes(flags.all("java-res"))
    val files = linkedMapOf<String, File>()
    for (root in flags.all("aar-root")) {
        collect(Path.of(root, "assets"), "assets/", files)
        collect(Path.of(root, "jni"), "lib/", files)
    }
    for (file in flags.all("file")) {
        val (path, source) = file.split("=", limit = 2)
        files[path] = File(source)
    }
    val minSdk = flags.one("min-sdk").toInt()
    // useLegacyPackaging unset: AGP's default is legacy (compressed) below minSdk 23 for native libraries and 28 for dex.
    val jniMode = PackagingUtils.getNativeLibrariesLibrariesPackagingMode(minSdk < 23)
    val dexMode = PackagingUtils.getDexPackagingMode(null, minSdk < 28)
    val noCompress = PackagingUtils.getNoCompressPredicate(listOf(), jniMode, dexMode)
    val data =
        ApkCreatorFactory.CreationData
            .builder()
            .setApkPath(File(flags.one("out")))
            .setNativeLibrariesPackagingMode(jniMode)
            .setNoCompressPredicate { noCompress.test(it) }
            .setIncremental(false)
    if ("keystore" in flags) {
        val store = KeyStore.getInstance(File(flags.one("keystore")), flags.one("store-password").toCharArray())
        val alias = flags.one("key-alias")
        data.setSigningOptions(
            SigningOptions
                .builder()
                .setKey(store.getKey(alias, flags.one("key-password").toCharArray()) as PrivateKey)
                .setCertificates(store.getCertificate(alias) as X509Certificate)
                .setV1SigningEnabled(false)
                .setV2SigningEnabled(true)
                .setMinSdkVersion(minSdk)
                .setValidation(SigningOptions.Validation.ASSUME_INVALID)
                .build(),
        )
    }
    val scratch = Files.createTempDirectory("apk")
    ApkFlinger(data.build(), Deflater.DEFAULT_COMPRESSION, true, false, false, ApkFlinger.PAGE_SIZE_16K).use { apk ->
        apk.writeZip(File(flags.one("resources")), null, null)
        val dexes = mutableListOf<Path>()
        for (dex in flags.all("dex")) {
            ZipFile(dex).use { zip ->
                for (entry in zip.stream().toList().filter { it.name.matches(Regex("classes\\d*\\.dex")) }) {
                    val file = scratch.resolve("dex" + dexes.size)
                    zip.getInputStream(entry).use { Files.copy(it, file) }
                    dexes.add(file)
                }
            }
        }
        dexes.forEachIndexed { i, dex -> apk.writeFile(dex.toFile(), if (i == 0) "classes.dex" else "classes${i + 1}.dex") }
        for ((path, file) in files) {
            apk.writeFile(file, path)
        }
        // AGP's merged Java resources are an apkzlib jar, whose compressor stores an entry deflating doesn't shrink,
        // and writeZip keeps each entry's compression.
        val resJar = scratch.resolve("java-res.jar")
        ZipOutputStream(Files.newOutputStream(resJar)).use { jar ->
            for ((path, bytes) in javaRes) {
                val entry = ZipEntry(path)
                if (deflatedSize(bytes) >= bytes.size) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                jar.putNextEntry(entry)
                jar.write(bytes)
                jar.closeEntry()
            }
        }
        apk.writeZip(resJar.toFile(), null, null)
        flags["agp-version"]?.let {
            val metadata = scratch.resolve("app-metadata.properties")
            metadata.writeText("appMetadataVersion=1.1\nandroidGradlePluginVersion=${flags.one("agp-version")}\n")
            apk.writeFile(metadata.toFile(), "META-INF/com/android/build/gradle/app-metadata.properties")
        }
    }
}

/**
 * MergeJavaResourceTask: the Java resources of the runtime jars, in classpath order, excluded, merged or the first one
 * kept, as one jar (R8's input in a release build).
 */
private fun javaRes(flags: Flags) {
    ZipOutputStream(Files.newOutputStream(Path.of(flags.one("out")))).use { out ->
        for ((path, bytes) in mergeJavaRes(flags.all("jar"))) {
            out.putNextEntry(ZipEntry(path).apply { timeLocal = ZIP_EPOCH })
            out.write(bytes)
            out.closeEntry()
        }
    }
}

/**
 * mergeReleaseArtProfile and expandReleaseArtProfileWildcards: the libraries' baseline-prof.txt joined (the app has
 * none of its own), then every wildcard rule expanded against the program's classes, as R8 takes it.
 */
private fun artProfile(flags: Flags) {
    val merged = Path.of(flags.one("out") + ".merged")
    merged.writeText(flags.all("profile").joinToString("\n") { Path.of(it).readText() })
    expandWildcards(merged.toString(), flags.one("out"), flags.all("jar"), FAIL_ON_PROFILE_ERROR)
    Files.delete(merged)
}

/**
 * minifyReleaseWithR8, as R8Task runs it: AGP's `runR8` with its defaults (full mode, strict full-mode keep rules, no
 * main-dex list), the merged Java resources, the ART profile R8 rewrites, and optimized resource shrinking over aapt2's
 * proto-format link (the AARs' raw resources give the `tools:keep` rules).
 */
private fun r8(flags: Flags) {
    val scratch = Files.createTempDirectory("r8")
    val rules: List<KeepRuleFile> = flags.all("keep-rules").map { KeepRuleFile.WithoutOrigin(it) }
    val outputs =
        ProguardOutputFiles(
            Path.of(flags.one("mapping")),
            scratch.resolve("mapping.prt"),
            scratch.resolve("seeds.txt"),
            scratch.resolve("usage.txt"),
            Path.of(flags.one("configuration")),
            scratch.resolve("missing_rules.txt"),
        )
    val keepConfig = ProguardConfig(rules, null, listOf(), outputs, null)
    val minSdk = flags.one("min-sdk").toInt()
    val tool = ToolConfig(minSdk, false, false, false, false, true, true, null, R8OutputType.DEX, true)
    val shrinking =
        ResourceShrinkingConfig(
            listOf(File(flags.one("resources"))),
            flags.all("raw-res").map(::File),
            listOf(),
            true,
            true,
            null,
            ResourceShrinkingConfig.ShrinkOutput(listOf(File(flags.one("shrunk-resources"))), null),
        )
    val dexDir = Files.createDirectories(scratch.resolve("dex"))
    // AGP logs R8's info messages (rules that match nothing) at Gradle's info level, which a build doesn't show.
    val messages =
        MessageReceiver { message ->
            if (message.kind != Message.Kind.INFO) {
                System.err.println("${message.kind}: ${message.text}")
            }
        }
    val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
    try {
        runR8(
            flags.all("program").map { Path.of(it) },
            dexDir,
            Path.of(flags.one("java-res")),
            Path.of(flags.one("java-res-out")),
            listOf(Path.of(flags.one("lib"))),
            listOf<Path>(),
            tool,
            keepConfig,
            MainDexListConfig(),
            shrinking,
            messages,
            listOf<Path>(),
            listOf<Path>(),
            null,
            null,
            null,
            flags.optionalPath("art-profile"),
            flags.optionalPath("art-profile-out"),
            null,
            null,
            null,
            pool,
        )
    } finally {
        pool.shutdown()
    }
    ZipOutputStream(Files.newOutputStream(Path.of(flags.one("dex")))).use { out ->
        Files.list(dexDir).use { dexes ->
            for (dex in dexes.toList().sorted()) {
                out.putNextEntry(ZipEntry(dex.fileName.toString()).apply { timeLocal = ZIP_EPOCH })
                Files.copy(dex, out)
                out.closeEntry()
            }
        }
    }
}

/**
 * compileReleaseArtProfile: R8's rewritten profile against the dex files (in the order they're packaged, no
 * obfuscation map: R8 already renamed the rules), saved for ART (P's format) and as its metadata.
 */
private fun compileArtProfile(flags: Flags) {
    val profile =
        HumanReadableProfile(File(flags.one("profile")), FAIL_ON_PROFILE_ERROR)
            ?: error("Merged baseline-prof.txt cannot be parsed successfully.")
    val scratch = Files.createTempDirectory("dex")
    val dexFiles = mutableListOf<File>()
    ZipFile(flags.one("dex")).use { zip ->
        for (entry in zip.stream().toList()) {
            val file = scratch.resolve(entry.name)
            zip.getInputStream(entry).use { Files.copy(it, file) }
            dexFiles += file.toFile()
        }
    }
    dexFiles.sortWith(DexFileComparator)
    val dex = mutableListOf<DexFile>()
    for (file in dexFiles) {
        Files.newInputStream(file.toPath()).buffered().use { dex += parseDexFiles(it, dex.size) }
    }
    val art = ArtProfile(profile, ObfuscationMap.Empty, dex, "")
    Files.newOutputStream(Path.of(flags.one("out"))).use { art.save(it, ArtProfileSerializer.V0_1_0_P) }
    Files.newOutputStream(Path.of(flags.one("out-metadata"))).use { art.save(it, ArtProfileSerializer.METADATA_0_0_2) }
}

/**
 * The VCS info AGP records in a release APK (`vcsInfo.include`): the commit from the workspace status's
 * `STABLE_GIT_COMMIT`, so only a stamped build (`--stamp`) has it.
 */
private fun vcsInfo(flags: Flags) {
    val revision =
        Path
            .of(flags.one("status"))
            .readLines()
            .lastOrNull { it.startsWith("STABLE_GIT_COMMIT ") }
            ?.removePrefix("STABLE_GIT_COMMIT ")
            ?.trim()
            ?: error("--stamp without STABLE_GIT_COMMIT: the workspace status command didn't run")
    Path
        .of(flags.one("out"))
        .writeText("repositories {\n  system: GIT\n  local_root_path: \"\$PROJECT_DIR\"\n  revision: \"$revision\"\n}\n")
}

private fun deflatedSize(bytes: ByteArray): Int {
    val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
    try {
        deflater.setInput(bytes)
        deflater.finish()
        val buffer = ByteArray(8192)
        var size = 0
        while (!deflater.finished()) {
            size += deflater.deflate(buffer)
        }
        return size
    } finally {
        deflater.end()
    }
}

private fun mergeJavaRes(jars: List<String>): Map<String, ByteArray> {
    val javaRes = linkedMapOf<String, ByteArray>()
    val owners = linkedMapOf<String, String>()
    for (jar in jars) {
        ZipInputStream(Files.newInputStream(Path.of(jar))).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val path = entry.name
                if (entry.isDirectory ||
                    path.endsWith(".dex") ||
                    !PackagingUtils.checkFileForApkPackaging(path, false) ||
                    EXCLUDES.matchesAny(path)
                ) {
                    continue
                }
                val bytes = input.readAllBytes()
                val existing = javaRes[path]
                when {
                    existing == null -> {
                        javaRes[path] = bytes
                        owners[path] = jar
                    }
                    MERGES.matchesAny(path) -> javaRes[path] = merge(existing, bytes)
                    !existing.contentEquals(bytes) ->
                        error("2 files found with path '$path' from inputs ${owners[path]} and $jar")
                }
            }
        }
    }
    return javaRes
}

/** A merged file's parts, each ending in a newline, as AGP's MergedFile joins service files. */
private fun merge(
    first: ByteArray,
    second: ByteArray,
): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(first)
    if (first.isNotEmpty() && first.last() != '\n'.code.toByte()) {
        out.write('\n'.code)
    }
    out.write(second)
    return out.toByteArray()
}

private fun collect(
    dir: Path,
    prefix: String,
    into: MutableMap<String, File>,
) {
    if (!dir.isDirectory()) {
        return
    }
    Files.walk(dir).use { walk ->
        for (file in walk.toList().filter { it.isRegularFile() }.sorted()) {
            into.putIfAbsent(prefix + dir.relativize(file).toString().replace(File.separatorChar, '/'), file.toFile())
        }
    }
}
