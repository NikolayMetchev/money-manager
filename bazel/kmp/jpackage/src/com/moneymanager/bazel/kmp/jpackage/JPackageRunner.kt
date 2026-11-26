package com.moneymanager.bazel.kmp.jpackage

import java.io.IOException
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant
import java.time.Year
import java.util.concurrent.TimeUnit
import java.util.spi.ToolProvider
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.absolute
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeLines
import kotlin.io.path.writeText

/*
 * Compose Desktop's `createRuntimeImage`, `createReleaseDistributable` and `package<Format>` (#1020), for
 * //bazel/kmp/jpackage. The one argument is a params file, one argument per line; the first is the mode:
 *
 * - `jlink <out> <jlink args…>`: jlink, in process, into `out`.
 * - `image <out> <options…> -- <jpackage args…>`: lays out the `--input` directory as Compose does (skiko's natives
 *   unpacked next to the jars, an empty `resources` directory), then jpackage `--type app-image`.
 * - `installer <out> <options…> -- <jpackage args…>`: jpackage's installer over an app image.
 * - `aot <out> <options…> -- <trainings…>`: an app image with an AOT cache per launcher (#1254); see [aot].
 *
 * In the jpackage arguments `{version}` is the version file's text, `{libs}`, `{runtime}`, `{resources}` and `{cli}`
 * the directories this runner lays out, and `{icon}` / `{entitlements}` Compose's defaults, read from the Compose
 * Gradle plugin's jar on this classpath (so they move with the catalog's Compose). Options: `--version-file F`,
 * `--jar F` (repeated), `--runtime DIR` (a jlinked runtime, copied writable: jpackage changes it in place, and Bazel's
 * inputs are read-only), `--cli F` (the native `mm`), `--app-image DIR`, `--wix DIR` (put on jpackage's `PATH`, as
 * Compose does on Windows), `--name N`, and on macOS `--bundle-id B` (else `--main-class C`'s package) for the
 * `Info.plist`, `--mac-package-identifier` and, with Compose's entitlements, `--mac-entitlements`.
 *
 * jpackage runs from this JVM's own JDK (the exec toolchain's), so it packages for the host, as it only can.
 */

private val HOST_OS = OS.current()
private val HOST_ARCH = arch()

/**
 * Every file in an app image (and, on install, every file the installer puts down) carries this modification time.
 * The JVM rejects an AOT cache when a jar on the classpath has another mtime than the one it was trained against, and
 * jpackage, Bazel's remote outputs and copies all restamp files, so the image is trained with its files at this time
 * and each installer is rewritten to install them at it. Whole seconds, even ones, so a cabinet's 2-second DOS times
 * hold it too.
 */
internal val PINNED_MTIME: FileTime = FileTime.from(Instant.parse("2020-01-01T00:00:00Z"))

private const val TRAINING_TIMEOUT_MINUTES = 10L
private const val TAIL_CHARS = 8000
private const val DETACH_ATTEMPTS = 10
private const val DETACH_RETRY_MILLIS = 3_000L

fun main(argv: Array<String>) {
    val args = Path.of(argv[0]).readLines()
    val mode = args[0]
    val out = Path.of(args[1]).absolute()
    val rest = args.subList(2, args.size)
    when (mode) {
        "jlink" -> jlink(out, rest)
        "image", "installer" -> jpackage(mode, out, rest)
        "aot" -> aot(out, rest)
        else -> throw IllegalArgumentException("unknown mode $mode")
    }
}

private fun jlink(
    out: Path,
    args: List<String>,
) {
    // Bazel creates a tree artifact's directory; jlink refuses to write into an existing one.
    deleteRecursively(out)
    val jlink = ToolProvider.findFirst("jlink").orElseThrow()
    val code = jlink.run(System.out, System.err, *(args + listOf("--output", out.toString())).toTypedArray())
    check(code == 0) { "jlink exited with $code" }
}

/** The `--name value` pairs before `--`, and the arguments after it. */
private fun splitOptions(args: List<String>): Pair<Map<String, List<String>>, List<String>> {
    val separator = args.indexOf("--")
    val options = linkedMapOf<String, MutableList<String>>()
    for (i in 0 until separator step 2) {
        options.getOrPut(args[i]) { mutableListOf() } += args[i + 1]
    }
    return options to args.subList(separator + 1, args.size)
}

private fun jpackage(
    mode: String,
    out: Path,
    args: List<String>,
) {
    val (options, jpackageArgs) = splitOptions(args)
    val work = Files.createTempDirectory("jpackage")
    try {
        val values = linkedMapOf<String, String>()
        values["{version}"] = Path.of(options.one("--version-file")).readText().trim()
        val resources = Files.createDirectories(work.resolve("resources"))
        values["{resources}"] = resources.toString()
        values["{icon}"] = composeResource(work, "default-compose-desktop-icon-" + HOST_OS.iconSuffix).toString()
        values["{entitlements}"] = composeResource(work, "default-entitlements.plist").toString()
        if (HOST_OS == OS.MAC) {
            writeInfoPlist(resources.resolve("Info.plist"), options, values.getValue("{version}"))
        }
        if (mode == "installer" && HOST_OS == OS.WINDOWS) {
            writeMainWxs(resources.resolve("main.wxs"))
        }
        options["--jar"]?.let { values["{libs}"] = layOutLibs(work.resolve("libs"), it, work).toString() }
        if ("--runtime" in options) {
            val runtime = work.resolve("runtime")
            copyWritable(Path.of(options.one("--runtime")), runtime)
            values["{runtime}"] = runtime.toString()
        }
        options["--cli"]?.let { values["{cli}"] = layOutCli(work.resolve("cli"), it).toString() }
        val jpackage = Path.of(System.getProperty("java.home"), "bin", HOST_OS.executable("jpackage"))
        val command = mutableListOf(jpackage.toString())
        for (arg in jpackageArgs) {
            command += values.entries.fold(arg) { text, (key, value) -> text.replace(key, value) }
        }
        if (HOST_OS == OS.MAC) {
            if (mode == "image") {
                command += listOf("--java-options", "-Xdock:name=" + options.one("--name"))
            }
            command += listOf("--mac-package-identifier", bundleId(options))
            command += listOf("--mac-entitlements", values.getValue("{entitlements}"))
        }
        if (mode == "installer") {
            val bundle = prepareInstallerBundle(work, options, Path.of(values.getValue("{entitlements}")))
            command += listOf("--app-image", bundle.toString())
        }
        command += listOf("--dest", out.toString())
        val process = ProcessBuilder(command).inheritIO()
        // jpackage stamps the files it packages with the time it copies them; dpkg-deb clamps every file's mtime to
        // SOURCE_DATE_EPOCH, so the deb installs them at the time the AOT caches were trained against.
        process.environment()["SOURCE_DATE_EPOCH"] = PINNED_MTIME.toInstant().epochSecond.toString()
        options["--wix"]?.let {
            val wix = Path.of(it.single()).absolute().toString()
            process.environment().merge("PATH", wix) { path, dir -> "$dir;$path" }
        }
        process.start().use { running ->
            val code = running.waitFor()
            check(code == 0) { "jpackage exited with $code: ${command.joinToString(" ")}" }
        }
        if (mode == "image" && HOST_OS == OS.MAC) {
            signRuntime(out.resolve(options.one("--name") + ".app"), Path.of(values.getValue("{entitlements}")))
        }
        if (mode == "installer" && HOST_OS == OS.MAC) {
            for (dmg in out.listDirectoryEntries("*.dmg")) {
                pinDmgMtimes(dmg, work)
            }
        }
    } finally {
        deleteQuietly(work)
    }
}

private fun prepareInstallerBundle(
    work: Path,
    options: Map<String, List<String>>,
    entitlements: Path,
): Path {
    // jpackage packages a copy: Bazel's outputs are read-only (codesign then can't re-sign the bundle) and,
    // outside the sandbox, may be symlinks into its cache, which the package would carry as they are.
    val image = work.resolve("app-image")
    if ("--app-image-archive" in options) {
        Files.createDirectories(image)
        val archive = Path.of(options.one("--app-image-archive")).absolute()
        // A drive letter in tar's archive argument is interpreted as a remote host by GNU tar.
        val tar =
            ProcessBuilder("tar", "-xf", archive.name, "-C", image.toString())
                .directory(archive.parent.toFile())
                .inheritIO()
        check(tar.start().use { it.waitFor() } == 0) { "Could not extract $archive" }
        Files.walk(image).use { paths -> paths.forEach(::makeWritable) }
    } else {
        copyWritable(Path.of(options.one("--app-image")).absolute(), image)
    }
    val name = options.one("--name")
    // jpackage 18+ wants the .app bundle itself on macOS (Compose passes it the same way).
    val bundle = if (HOST_OS == OS.MAC) image.resolve("$name.app") else image.resolve(name)
    options["--installer-cli"]?.let { cli ->
        val content =
            when (HOST_OS) {
                OS.LINUX -> bundle.resolve("lib")
                OS.MAC -> bundle.resolve("Contents")
                OS.WINDOWS -> bundle
            }
        layOutCli(content.resolve("cli"), cli)
        if (HOST_OS == OS.MAC) {
            adHocSign(bundle, entitlements)
        }
    }
    return bundle
}

/**
 * Trains an AOT cache (JEP 483/514/515) for each of an app image's launchers, into a copy of the image. Options:
 * `--image DIR` (a `jpackage_app_image`), `--name N` and `--database F` (the database each training run opens, a copy
 * each time). After `--`, one block per launcher: `@launcher NAME`, then `@env K=V` lines and the launcher's
 * arguments, one per line. `{probe}` in an env value is a file in the run's scratch directory.
 *
 * For each launcher, the cache is built in steps through the launcher itself, since the jlinked runtime has no
 * `bin/java` for `-XX:AOTCacheOutput`'s child JVM:
 *
 * 1. Record: run the training with `-XX:AOTMode=record`, in a home of its own (the JVM's `user.home` as well as
 *    `HOME`) holding a copy of the database as the last one opened.
 * 2. Create: run the launcher again with `-XX:AOTMode=create`, which writes `<launcher>.aot` next to the jars and
 *    exits.
 * 3. Point the launcher's `.cfg` at the cache, with the JVM's warnings on stderr, so a stale cache can't corrupt the
 *    CLI's JSON.
 * 4. Run the training again with `-XX:AOTMode=on`, which fails unless the cache loads.
 *
 * On Linux without a `DISPLAY`, the runs get an Xvfb of their own.
 */
private fun aot(
    out: Path,
    args: List<String>,
) {
    val (options, trainingLines) = splitOptions(args)
    val trainings = Training.parse(trainingLines)
    val name = options.one("--name")
    val database = Path.of(options.one("--database")).absolute()
    deleteRecursively(out)
    copyWritable(Path.of(options.one("--image")).absolute(), out)
    pinMtimes(out)
    val app = appDirectory(out, name)
    val work = Files.createTempDirectory("aot")
    val display = linkedMapOf<String, String>()
    try {
        val xvfb = if (HOST_OS == OS.LINUX && System.getenv("DISPLAY") == null) startXvfb(display) else null
        try {
            for (training in trainings) {
                val launcher = launcher(out, name, training.launcher)
                val config = work.resolve(training.launcher + ".aotconf")
                val cache = app.resolve(training.launcher + ".aot")
                run(training, launcher, database, work, display, "-XX:AOTMode=record -XX:AOTConfiguration=$config")
                createCache(launcher, work, config, cache)
                pointConfigAtCache(app.resolve(training.launcher + ".cfg"), training.launcher)
                run(training, launcher, database, work, display, "-XX:AOTMode=on")
                Files.setLastModifiedTime(cache, PINNED_MTIME)
            }
            if (HOST_OS == OS.MAC && HOST_ARCH == "arm64") {
                // The .cfg files and caches are inside the signed bundle.
                adHocSign(out.resolve("$name.app"), composeResource(work, "default-entitlements.plist"))
            }
        } finally {
            // Process.close() waits for the process to end, so it's destroyed first.
            xvfb?.destroy()
            xvfb?.close()
        }
    } finally {
        deleteQuietly(work)
    }
}

/** Starts a private Xvfb and puts its `DISPLAY` in [display]. */
private fun startXvfb(display: MutableMap<String, String>): Process {
    val xvfb =
        ProcessBuilder("Xvfb", "-displayfd", "1", "-nolisten", "tcp", "-screen", "0", "1280x1024x24")
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    val number = String(xvfb.inputStream.readNBytes(16)).trim().split(Regex("\\s+"))[0]
    display["DISPLAY"] = ":$number"
    return xvfb
}

/** One launcher's training run: its name, environment and arguments. */
private class Training(
    val launcher: String,
) {
    val env = linkedMapOf<String, String>()
    val args = mutableListOf<String>()

    companion object {
        fun parse(lines: List<String>): List<Training> {
            val trainings = mutableListOf<Training>()
            for (line in lines) {
                when {
                    line.startsWith("@launcher ") -> trainings += Training(line.removePrefix("@launcher "))
                    trainings.isEmpty() -> throw IllegalArgumentException("training line before any @launcher: $line")
                    line.startsWith("@env ") -> {
                        val (key, value) = line.removePrefix("@env ").split("=", limit = 2)
                        trainings.last().env[key] = value
                    }
                    else -> trainings.last().args += line
                }
            }
            return trainings
        }
    }
}

/** Runs a launcher's training in a fresh home, with [jvmOptions] added; fails unless it exits 0. */
private fun run(
    training: Training,
    launcher: Path,
    database: Path,
    work: Path,
    display: Map<String, String>,
    jvmOptions: String,
) {
    val run = Files.createTempDirectory(work, training.launcher)
    val home = Files.createDirectories(run.resolve("home"))
    val data = Files.createDirectories(home.resolve(".moneymanager"))
    val copy = data.resolve("training.db")
    Files.copy(database, copy)
    makeWritable(copy)
    data.resolve("settings.json").writeText("{\"lastDatabase\":\"" + jsonEscape(copy.toString()) + "\"}\n")
    val command = listOf(launcher.toString()) + training.args
    val output = run.resolve("output.txt")
    val process =
        ProcessBuilder(command)
            .directory(run.toFile())
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
    val env = process.environment()
    isolate(env, home)
    env.putAll(display)
    for ((key, value) in training.env) {
        env[key] = value.replace("{probe}", run.resolve("probe.json").toString())
    }
    env["JAVA_TOOL_OPTIONS"] = "-Duser.home=$home $jvmOptions"
    waitFor(process.start(), command, output)
}

/**
 * `-XX:AOTMode=create` reads the recorded configuration and writes the cache; the app doesn't run.
 *
 * Without the adapter and stub code: that part records the training machine's CPU features, and a JVM on a CPU that
 * lacks any of them (no AVX-512, or not a VM) refuses the whole cache under `-XX:AOTMode=on` and drops it otherwise.
 * The classes, linking and profiles that make startup faster are CPU-neutral; measured, leaving the code out costs
 * nothing.
 */
private fun createCache(
    launcher: Path,
    work: Path,
    config: Path,
    cache: Path,
) {
    val output = work.resolve("${cache.fileName}.create.txt")
    val process =
        ProcessBuilder(launcher.toString())
            .directory(work.toFile())
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
    isolate(process.environment(), work)
    process.environment()["JAVA_TOOL_OPTIONS"] =
        "-XX:AOTMode=create -XX:AOTConfiguration=$config -XX:AOTCache=$cache" +
        " -XX:+UnlockDiagnosticVMOptions -XX:-AOTAdapterCaching -XX:-AOTStubCaching"
    waitFor(process.start(), listOf(launcher.toString(), "(AOTMode=create)"), output)
    check(cache.isRegularFile()) { "$launcher wrote no AOT cache:\n${output.readText()}" }
}

/**
 * Adds the cache to the launcher's `[JavaOptions]`, with the JVM's own warnings sent to stderr: by default they go to
 * stdout, where a stale cache's would land in the middle of the CLI's JSON.
 */
private fun pointConfigAtCache(
    cfg: Path,
    launcher: String,
) {
    val lines = cfg.readLines().toMutableList()
    val section = lines.indexOf("[JavaOptions]")
    check(section >= 0) { "$cfg has no [JavaOptions]" }
    var at = section + 1
    while (at < lines.size && !lines[at].startsWith("[") && lines[at].isNotBlank()) {
        at++
    }
    lines.addAll(
        at,
        listOf(
            $$"java-options=-XX:AOTCache=$APPDIR/$$launcher.aot",
            "java-options=-Xlog:disable",
            "java-options=-Xlog:all=warning:stderr",
        ),
    )
    cfg.writeLines(lines)
    Files.setLastModifiedTime(cfg, PINNED_MTIME)
}

private fun isolate(
    env: MutableMap<String, String>,
    home: Path,
) {
    env["HOME"] = home.toString()
    env["USERPROFILE"] = home.toString()
    env["APPDATA"] = home.resolve("AppData/Roaming").toString()
    env["LOCALAPPDATA"] = home.resolve("AppData/Local").toString()
    for (name in listOf(
        "MM_STARTUP_PROBE",
        "MM_STARTUP_PROBE_ACCOUNT",
        "MM_VAULT_PASSWORD",
        "MM_REMOTE_PASSWORD",
        "JAVA_TOOL_OPTIONS",
        "JDK_JAVA_OPTIONS",
        "_JAVA_OPTIONS",
    )) {
        env.remove(name)
    }
}

private fun waitFor(
    process: Process,
    command: List<String>,
    output: Path,
) {
    if (!process.waitFor(TRAINING_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
        process.destroyForcibly()
        error("${command.joinToString(" ")} timed out:\n${tail(output)}")
    }
    check(process.exitValue() == 0) { "${command.joinToString(" ")} exited ${process.exitValue()}:\n${tail(output)}" }
}

private fun tail(output: Path): String = (if (output.exists()) output.readText() else "").takeLast(TAIL_CHARS)

private fun jsonEscape(text: String): String = text.replace("\\", "\\\\").replace("\"", "\\\"")

/** The directory holding the jars and launcher `.cfg`s. */
private fun appDirectory(
    image: Path,
    name: String,
): Path =
    when (HOST_OS) {
        OS.MAC -> image.resolve("$name.app/Contents/app")
        OS.WINDOWS -> image.resolve(name).resolve("app")
        OS.LINUX -> image.resolve(name).resolve("lib/app")
    }

private fun launcher(
    image: Path,
    name: String,
    launcher: String,
): Path =
    when (HOST_OS) {
        OS.MAC -> image.resolve("$name.app/Contents/MacOS").resolve(launcher)
        OS.WINDOWS -> image.resolve(name).resolve("$launcher.exe")
        OS.LINUX -> image.resolve(name).resolve("bin").resolve(launcher)
    }

/**
 * jpackage's own `main.wxs`, read from this JDK, with one deferred custom action after `InstallFiles` that sets the
 * jars in `app\` back to [PINNED_MTIME]. A cabinet keeps file times as local time, and Windows Installer converts them
 * with the installing machine's time zone, so off UTC every jar lands hours away from the time its AOT cache was
 * trained against, and the JVM ignores the cache. Only the classpath jars are checked (not the runtime, the `.cfg` or
 * the cache itself).
 *
 * The action runs in `INSTALLDIR`, so the command names no path (`'` in a user's profile directory would end its
 * string) and no `[...]`, which Windows Installer would format as a property. It never fails the install: without it
 * the app only starts without its cache.
 */
private fun writeMainWxs(target: Path) {
    // This JDK's own image (the jpackage that runs): the jrt file system reads a module's resources whether or not it
    // opens them.
    val jrt = FileSystems.getFileSystem(URI.create("jrt:/"))
    var wxs = jrt.getPath("modules", "jdk.jpackage", "jdk/jpackage/internal/resources/main.wxs").readText()
    val command =
        "&quot;[System64Folder]WindowsPowerShell\\v1.0\\powershell.exe&quot; -NoProfile -NonInteractive" +
            " -WindowStyle Hidden -Command &quot;Get-ChildItem -LiteralPath app -Filter *.jar | ForEach-Object {" +
            $$" $_.LastWriteTimeUtc = (Get-Date '" + PINNED_MTIME.toInstant() + "').ToUniversalTime() }&quot;"
    wxs =
        insertBefore(
            wxs,
            "    <InstallExecuteSequence>",
            "    <CustomAction Id=\"MmPinJarTimes\" Directory=\"INSTALLDIR\" Execute=\"deferred\" Impersonate=\"yes\"" +
                " Return=\"ignore\" ExeCommand=\"" + command + "\" />\n\n",
        )
    wxs =
        insertBefore(
            wxs,
            "    </InstallExecuteSequence>",
            "      <Custom Action=\"MmPinJarTimes\" After=\"InstallFiles\">NOT REMOVE</Custom>\n",
        )
    target.writeText(wxs)
}

/** Inserts [text] before the one line that is [anchor]; jpackage's template changing fails loudly. */
private fun insertBefore(
    source: String,
    anchor: String,
    text: String,
): String {
    val lines = Regex("^" + Regex.escape(anchor) + "\r?$", RegexOption.MULTILINE).findAll(source).toList()
    check(lines.isNotEmpty()) { "jpackage's main.wxs no longer has the line \"$anchor\"" }
    check(lines.size == 1) { "jpackage's main.wxs has the line \"$anchor\" more than once" }
    val at = lines.single().range.first
    return source.substring(0, at) + text + source.substring(at)
}

/**
 * Rewrites a dmg with every file in it at [PINNED_MTIME]: jpackage stamps them with the time it copies them. A copy
 * that keeps modification times (Finder's, `ditto`, `cp -p`) then keeps the AOT caches valid. The modification time
 * isn't part of the code signature.
 */
private fun pinDmgMtimes(
    dmg: Path,
    work: Path,
) {
    val writable = work.resolve("writable.dmg")
    val mount = Files.createDirectories(work.resolve("dmg"))
    run(listOf("hdiutil", "convert", dmg.toString(), "-format", "UDRW", "-o", writable.toString()))
    run(listOf("hdiutil", "attach", "-nobrowse", "-noverify", "-mountpoint", mount.toString(), writable.toString()))
    try {
        // Only the bundles: the volume's own .fseventsd isn't ours to touch.
        for (app in mount.listDirectoryEntries("*.app")) {
            pinMtimes(app)
        }
    } finally {
        detach(mount)
    }
    Files.delete(dmg)
    run(listOf("hdiutil", "convert", writable.toString(), "-format", "UDZO", "-o", dmg.toString()))
    Files.delete(writable)
}

/**
 * Unmounts the dmg, retrying while it is busy: right after the volume is mounted and written, macOS (Spotlight,
 * diskarbitrationd) can still hold it open, and `hdiutil detach` then fails with "Resource busy" (16). Not `-force`:
 * the volume must be cleanly unmounted before it is converted back.
 */
private fun detach(mount: Path) {
    val command = listOf("hdiutil", "detach", mount.toString())
    var attempt = 1
    while (true) {
        val code = exitCode(command)
        if (code == 0) {
            return
        }
        check(attempt < DETACH_ATTEMPTS) {
            "${command.joinToString(" ")} exited with $code on all $DETACH_ATTEMPTS attempts"
        }
        System.err.println("${command.joinToString(" ")} exited with $code; retrying ($attempt/$DETACH_ATTEMPTS)")
        Thread.sleep(DETACH_RETRY_MILLIS)
        attempt++
    }
}

private fun run(command: List<String>) {
    val code = exitCode(command)
    check(code == 0) { "${command.joinToString(" ")} exited with $code" }
}

private fun exitCode(command: List<String>): Int = ProcessBuilder(command).inheritIO().start().use { it.waitFor() }

/** Sets every file's modification time to [PINNED_MTIME] (directories too, which nothing checks). */
private fun pinMtimes(root: Path) {
    Files.walk(root).use { walk ->
        for (path in walk.toList()) {
            if (!path.isSymbolicLink()) {
                Files.setLastModifiedTime(path, PINNED_MTIME)
            }
        }
    }
}

/** Copies a directory tree, every file left writable by its owner. */
private fun copyWritable(
    from: Path,
    to: Path,
) {
    Files.walk(from).use { walk ->
        for (source in walk.toList()) {
            val target = to.resolve(from.relativize(source).toString())
            if (source.isDirectory()) {
                Files.createDirectories(target)
            } else {
                Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES)
            }
            makeWritable(target)
        }
    }
}

/**
 * Compose's `prepareWorkingDir`: every jar copied as is, except the one holding the host's skiko natives (skiko's
 * runtime jar, or the one jar R8 shrinks the app into), whose native library (and its `.sha256`) is unpacked next to
 * the jars (skiko finds it through `skiko.library.path=$APPDIR`). On macOS, dylibs inside jars are ad-hoc signed
 * first, as Compose's unsigned build does on Apple Silicon.
 */
private fun layOutLibs(
    libs: Path,
    jars: List<String>,
    work: Path,
): Path {
    Files.createDirectories(libs.resolve("resources"))
    val skikoNatives = HOST_OS.skikoNatives()
    val signDylibs = HOST_OS == OS.MAC && HOST_ARCH == "arm64"
    for (jar in jars) {
        val source = Path.of(jar)
        val target = libs.resolve(source.fileName.toString())
        val skiko = holdsAny(source, skikoNatives)
        if (!skiko && !signDylibs) {
            Files.copy(source, target)
            // Bazel's inputs are read-only, and Windows won't delete a read-only copy.
            makeWritable(target)
            continue
        }
        ZipInputStream(Files.newInputStream(source)).use { input ->
            ZipOutputStream(Files.newOutputStream(target)).use { zip ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    val name = entry.name
                    val leaf = name.substringAfterLast('/')
                    if (skiko && leaf.removeSuffix(".sha256") in skikoNatives) {
                        Files.copy(input, libs.resolve(leaf))
                        continue
                    }
                    zip.putNextEntry(ZipEntry(name).apply { time = entry.time })
                    if (signDylibs && isDylib(name)) {
                        val dylib = work.resolve("sign").resolve(leaf)
                        Files.createDirectories(dylib.parent)
                        Files.copy(input, dylib, StandardCopyOption.REPLACE_EXISTING)
                        adHocSign(dylib, null)
                        Files.copy(dylib, zip)
                    } else {
                        input.transferTo(zip)
                    }
                    zip.closeEntry()
                }
            }
        }
    }
    return libs
}

/** `--app-content`: jpackage copies the directory into the app under its own name, `cli`. */
private fun layOutCli(
    cli: Path,
    executables: List<String>,
): Path {
    Files.createDirectories(cli)
    for (executable in executables) {
        val target = cli.resolve(HOST_OS.executable("mm"))
        Files.copy(Path.of(executable), target)
        if (HOST_OS != OS.WINDOWS) {
            Files.setPosixFilePermissions(
                target,
                Files.getPosixFilePermissions(target) +
                    setOf(
                        PosixFilePermission.OWNER_EXECUTE,
                        PosixFilePermission.GROUP_EXECUTE,
                        PosixFilePermission.OTHERS_EXECUTE,
                    ),
            )
        }
    }
    return cli
}

/** Compose's `setInfoPlistValues`, for the options this build sets. */
private fun writeInfoPlist(
    plist: Path,
    options: Map<String, List<String>>,
    version: String,
) {
    val name = options.one("--name")
    val values =
        linkedMapOf(
            "LSMinimumSystemVersion" to "10.13",
            "CFBundleDevelopmentRegion" to "English",
            "CFBundleAllowMixedLocalizations" to "true",
            "CFBundleExecutable" to name,
            "CFBundleIconFile" to "$name.icns",
            "CFBundleIdentifier" to bundleId(options),
            "CFBundleInfoDictionaryVersion" to "6.0",
            "CFBundleName" to name,
            "CFBundlePackageType" to "APPL",
            "CFBundleShortVersionString" to version,
            "LSApplicationCategoryType" to "Unknown",
            "CFBundleVersion" to version,
            "NSHumanReadableCopyright" to "Copyright (C) " + Year.now().value,
            "NSSupportsAutomaticGraphicsSwitching" to "true",
            "NSHighResolutionCapable" to "true",
        )
    plist.writeText(
        buildString {
            appendLine("<?xml version=\"1.0\" ?>")
            append("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" ")
            appendLine("\"https://www.apple.com/DTDs/PropertyList-1.0.dtd\">")
            appendLine("<plist version=\"1.0\">")
            appendLine("  <dict>")
            for ((key, value) in values) {
                appendLine("    <key>$key</key>")
                appendLine("    <string>$value</string>")
            }
            appendLine("  </dict>")
            appendLine("</plist>")
        },
    )
}

/** Compose's default bundle id is the main class's package. */
private fun bundleId(options: Map<String, List<String>>): String =
    if ("--bundle-id" in options) options.one("--bundle-id") else options.one("--main-class").substringBeforeLast('.')

/**
 * Compose's `modifyRuntimeOnMacOsIfNeeded` without a signing identity: on Apple Silicon every executable and dylib in
 * the bundled runtime, then the runtime and the app themselves, are re-signed ad hoc with Compose's default
 * entitlements (the JIT needs them under the hardened runtime).
 */
private fun signRuntime(
    app: Path,
    entitlements: Path,
) {
    if (HOST_ARCH != "arm64") {
        return
    }
    val runtime = app.resolve("Contents/runtime")
    val files =
        Files.walk(runtime).use { walk ->
            walk
                .filter { it.isRegularFile(LinkOption.NOFOLLOW_LINKS) && (it.isExecutable() || isDylib(it.name)) }
                .toList()
        }
    for (file in files) {
        adHocSign(file, entitlements)
    }
    adHocSign(runtime, entitlements)
    adHocSign(app, entitlements)
}

private fun adHocSign(
    file: Path,
    entitlements: Path?,
) {
    codesign(listOf("-vvvv", "--remove-signature", file.toString()))
    val args = mutableListOf("-vvvv", "--sign", "-", "--options", "runtime", "--force")
    if (entitlements != null) {
        args += listOf("--entitlements", entitlements.toString())
    }
    codesign(args + file.toString())
}

private fun codesign(args: List<String>) {
    val command = listOf("/usr/bin/codesign") + args
    ProcessBuilder(command).redirectErrorStream(true).start().use { process ->
        // codesign -vvvv is chatty: keep its output for a failure only.
        val output = process.inputStream.readAllBytes()
        if (process.waitFor() != 0) {
            System.err.write(output)
            error("codesign failed: ${command.joinToString(" ")}")
        }
    }
}

/** Whether the jar has an entry whose file name is one of [leaves]. */
private fun holdsAny(
    jar: Path,
    leaves: Set<String>,
): Boolean = ZipFile(jar.toFile()).use { zip -> zip.stream().anyMatch { it.name.substringAfterLast('/') in leaves } }

private fun isDylib(name: String): Boolean = name.endsWith(".dylib") || name.endsWith(".jnilib")

private fun composeResource(
    work: Path,
    name: String,
): Path {
    val target = work.resolve(name)
    val resource =
        ClassLoader.getSystemResourceAsStream(name) ?: error("$name isn't on the classpath (compose-gradle-plugin)")
    resource.use { Files.copy(it, target) }
    return target
}

private fun Map<String, List<String>>.one(name: String): String {
    val values = this[name]
    require(values != null && values.size == 1) { "expected one $name, got $values" }
    return values.single()
}

private fun deleteRecursively(dir: Path) {
    if (!dir.exists(LinkOption.NOFOLLOW_LINKS)) {
        return
    }
    Files.walk(dir).use { walk ->
        for (path in walk.sorted(Comparator.reverseOrder()).toList()) {
            // Windows won't delete a read-only file.
            makeWritable(path)
            Files.delete(path)
        }
    }
}

/** Deletes [dir], only warning on failure: a temp directory left behind mustn't hide what happened. */
private fun deleteQuietly(dir: Path) {
    try {
        deleteRecursively(dir)
    } catch (e: IOException) {
        System.err.println("warning: couldn't delete $dir: $e")
    }
}

private fun makeWritable(path: Path) {
    // Windows has no write bit on a directory (setWritable says false) and deletes it regardless.
    if (!path.toFile().setWritable(true, true) && !path.isDirectory()) {
        throw IOException("can't make $path writable")
    }
}

private fun arch(): String {
    val arch = System.getProperty("os.arch")
    return if (arch == "aarch64" || arch == "arm64") "arm64" else "x64"
}

private enum class OS(
    val iconSuffix: String,
) {
    LINUX("linux.png"),
    MAC("mac.icns"),
    WINDOWS("windows.ico"),
    ;

    fun executable(name: String): String = if (this == WINDOWS) "$name.exe" else name

    /** Compose's `unpackSkikoForCurrentOS`. */
    fun skikoNatives(): Set<String> =
        when (this) {
            MAC -> setOf("libskiko-macos-$HOST_ARCH.dylib")
            WINDOWS -> setOf("skiko-windows-$HOST_ARCH.dll", "icudtl.dat")
            LINUX -> setOf("libskiko-linux-$HOST_ARCH.so")
        }

    companion object {
        fun current(): OS {
            val name = System.getProperty("os.name").lowercase()
            return when {
                name.startsWith("mac") -> MAC
                name.startsWith("windows") -> WINDOWS
                else -> LINUX
            }
        }
    }
}
