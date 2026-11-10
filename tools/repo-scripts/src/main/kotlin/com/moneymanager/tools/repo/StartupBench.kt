package com.moneymanager.tools.repo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

/**
 * Startup benchmark for the desktop app, mm-jvm and native mm (#1254).
 *
 * Each run gets a home of its own (HOME, USERPROFILE, APPDATA, LOCALAPPDATA, and -Duser.home for the JVM, which
 * otherwise reads the password database rather than HOME) holding a fresh copy of the benchmark database as its last
 * database, so a run never sees the real ~/.moneymanager and never sees another run's changes. Results go to stdout,
 * or --out, as JSON lines, one per (runtime, scenario, variant, metric):
 *
 *     {"sha", "commit_date", "date", "os", "arch", "label", "runtime", "scenario", "variant", "metric",
 *      "median_ms", "min_ms", "max_ms", "runs"}
 *
 * Variants: "cache" runs a launcher as shipped (with its AOT cache, when it has one); "nocache" adds
 * -XX:AOTMode=off. Native mm has no JVM, so it runs once, as "native".
 *
 * The desktop app reports its milestones through the startup probe (MM_STARTUP_PROBE) and closes itself; on Linux it
 * needs a display (DISPLAY, e.g. Xvfb).
 */
private const val BENCH_ACCOUNT = "Alex Current Account"
private val VARIANT_FLAGS = mapOf("cache" to emptyList(), "nocache" to listOf("-XX:AOTMode=off"))
private const val DESKTOP_TIMEOUT_S = 300L
private const val CLI_TIMEOUT_S = 120L
private val WINDOWS = System.getProperty("os.name").startsWith("Windows")

private class BenchArgs(
    val benchDb: String,
    val emptyDb: String?,
    val desktop: String?,
    val mmJvm: String?,
    val mm: String?,
    val runs: Int,
    val variants: List<String>,
    val sha: String,
    val commitDate: String,
    val label: String,
    val out: String?,
)

private fun parseArgs(args: List<String>): BenchArgs {
    val options = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val name = args[i]
        if (!name.startsWith("--") || i + 1 >= args.size) fail("expected --option value, got ${args.drop(i)}")
        options[name.removePrefix("--")] = args[i + 1]
        i += 2
    }
    val known = setOf("bench-db", "empty-db", "desktop", "mm-jvm", "mm", "runs", "variants", "sha", "commit-date", "label", "out")
    (options.keys - known).firstOrNull()?.let { fail("unknown option --$it") }
    val variants = (options["variants"] ?: "cache,nocache").split(",").filter { it.isNotEmpty() }
    variants.firstOrNull { it !in VARIANT_FLAGS }?.let { fail("unknown variant $it") }
    return BenchArgs(
        benchDb = options["bench-db"] ?: fail("--bench-db is required"),
        emptyDb = options["empty-db"]?.takeIf { it.isNotBlank() },
        desktop = options["desktop"]?.takeIf { it.isNotBlank() },
        mmJvm = options["mm-jvm"]?.takeIf { it.isNotBlank() },
        mm = options["mm"]?.takeIf { it.isNotBlank() },
        runs = options["runs"]?.toInt() ?: 5,
        variants = variants,
        sha = options["sha"] ?: System.getenv("GITHUB_SHA").orEmpty(),
        commitDate = options["commit-date"].orEmpty(),
        label = options["label"].orEmpty(),
        out = options["out"],
    )
}

private fun isolatedEnv(
    builder: ProcessBuilder,
    home: File,
    javaFlags: List<String>,
) {
    val env = builder.environment()
    env["HOME"] = home.path
    env["USERPROFILE"] = home.path
    if (WINDOWS) {
        env["APPDATA"] = File(home, "AppData/Roaming").also { it.mkdirs() }.path
        env["LOCALAPPDATA"] = File(home, "AppData/Local").also { it.mkdirs() }.path
    }
    env["JAVA_TOOL_OPTIONS"] = (listOf("-Duser.home=${home.path}") + javaFlags).joinToString(" ")
    for (name in listOf("MM_STARTUP_PROBE", "MM_STARTUP_PROBE_ACCOUNT", "MM_VAULT_PASSWORD", "MM_REMOTE_PASSWORD")) env.remove(name)
}

internal fun makeHome(
    root: File,
    db: String,
): File {
    val home = Files.createTempDirectory(root.toPath(), "home-").toFile()
    val data = File(home, ".moneymanager").also { it.mkdirs() }
    val copy = File(data, "bench.db")
    File(db).copyTo(copy)
    File(data, "settings.json").writeText(Json.encodeToString(BenchSettings(lastDatabase = copy.path)))
    return home
}

/** Runs [command] to its end, with its output (both streams) in a file so a full pipe never stalls it. */
private fun run(
    command: List<String>,
    home: File,
    javaFlags: List<String>,
    timeoutS: Long,
    extraEnv: Map<String, String> = emptyMap(),
): Triple<Double, Int, String> {
    val log = File(home, "output.log")
    val builder =
        ProcessBuilder(command)
            .directory(home)
            .redirectErrorStream(true)
            .redirectOutput(log)
            .redirectInput(ProcessBuilder.Redirect.from(File(if (WINDOWS) "NUL" else "/dev/null")))
    isolatedEnv(builder, home, javaFlags)
    builder.environment().putAll(extraEnv)
    val start = System.nanoTime()
    val process = builder.start()
    if (!process.waitFor(timeoutS, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        fail("${command.first()} didn't finish in ${timeoutS}s")
    }
    val elapsedMs = (System.nanoTime() - start) / 1e6
    return Triple(elapsedMs, process.exitValue(), log.readText())
}

private fun timeCli(
    launcher: String,
    args: List<String>,
    home: File,
    javaFlags: List<String>,
): Map<String, Double> {
    val (elapsed, code, output) = run(listOf(launcher) + args, home, javaFlags, CLI_TIMEOUT_S)
    check(code == 0) { "$launcher ${args.joinToString(" ")} exited $code:\n$output" }
    return mapOf("wall" to elapsed)
}

private fun timeDesktop(
    launcher: String,
    account: String?,
    home: File,
    javaFlags: List<String>,
): Map<String, Double> {
    val probe = File(home, "probe.json")
    val extra = mutableMapOf("MM_STARTUP_PROBE" to probe.path)
    if (account != null) extra["MM_STARTUP_PROBE_ACCOUNT"] = account
    val (elapsed, code, output) = run(listOf(launcher), home, javaFlags, DESKTOP_TIMEOUT_S, extra)
    check(probe.exists()) { "$launcher exited $code without writing the probe:\n${output.takeLast(4000)}" }
    val metrics = Json.decodeFromString<Map<String, Double>>(probe.readText())
    return metrics + ("wall" to elapsed)
}

private class Scenario(
    val runtime: String,
    val name: String,
    val variants: List<String>,
    val db: String,
    val measure: (File, List<String>) -> Map<String, Double>,
)

private fun scenarios(args: BenchArgs): List<Scenario> {
    val out = mutableListOf<Scenario>()
    val dbs = listOf("bench" to args.benchDb) + listOfNotNull(args.emptyDb?.let { "empty" to it })
    for ((dbName, db) in dbs) {
        val cli = mutableListOf("accounts" to listOf("--format", "json", "accounts"))
        if (dbName == "bench") {
            cli += "transactions" to listOf("--format", "json", "transactions", "--account", "^$BENCH_ACCOUNT$")
        }
        for ((runtime, launcher, variants) in listOf(
            Triple("mm-jvm", args.mmJvm, args.variants),
            Triple("mm", args.mm, listOf("native")),
        )) {
            if (launcher == null) continue
            for ((scenario, cliArgs) in cli) {
                out += Scenario(runtime, "$scenario-$dbName", variants, db) { home, flags -> timeCli(launcher, cliArgs, home, flags) }
            }
        }
        args.desktop?.let { desktop ->
            val account = if (dbName == "bench") BENCH_ACCOUNT else null
            out += Scenario("desktop", "startup-$dbName", args.variants, db) { home, flags -> timeDesktop(desktop, account, home, flags) }
        }
    }
    return out
}

internal fun median(values: List<Double>): Double {
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
}

internal fun round1(value: Double): Double = (value * 10).roundToLong() / 10.0

private fun osName(): String {
    val name = System.getProperty("os.name")
    return when {
        name.startsWith("Linux") -> "linux"
        name.startsWith("Mac") -> "macos"
        name.startsWith("Windows") -> "windows"
        else -> name.lowercase()
    }
}

/** The architecture as the history's first recordings spelled it (Python's platform.machine()), so the recorded history keeps one spelling. */
private fun archName(): String {
    val arch = System.getProperty("os.arch").lowercase()
    val os = osName()
    return when (arch) {
        "aarch64" -> if (os == "macos") "arm64" else arch
        "amd64" -> if (os == "linux") "x86_64" else arch
        else -> arch
    }
}

/** The part of `settings.json` the benchmark's home needs: the database the launchers open. */
@Serializable
internal class BenchSettings(
    val lastDatabase: String,
)

/** One line of the results: the shape `bazel/perf/record.sh` appends to `startup.jsonl` and `performance.html` graphs. */
@Serializable
internal data class BenchRecord(
    val sha: String,
    @SerialName("commit_date") val commitDate: String,
    val date: String,
    val os: String,
    val arch: String,
    val label: String,
    val runtime: String,
    val scenario: String,
    val variant: String,
    val metric: String,
    @SerialName("median_ms") val medianMs: Double,
    @SerialName("min_ms") val minMs: Double,
    @SerialName("max_ms") val maxMs: Double,
    val runs: Int,
)

private fun record(
    args: BenchArgs,
    date: String,
    scenario: Scenario,
    variant: String,
    metric: String,
    values: List<Double>,
) = BenchRecord(
    sha = args.sha,
    commitDate = args.commitDate,
    date = date,
    os = osName(),
    arch = archName(),
    label = args.label,
    runtime = scenario.runtime,
    scenario = scenario.name,
    variant = variant,
    metric = metric,
    medianMs = round1(median(values)),
    minMs = round1(values.min()),
    maxMs = round1(values.max()),
    runs = values.size,
)

/** One warm-up run, discarded, then [BenchArgs.runs] measured ones, each in a home of its own. */
private fun sample(
    args: BenchArgs,
    root: File,
    scenario: Scenario,
    variant: String,
): Map<String, List<Double>> {
    val samples = linkedMapOf<String, MutableList<Double>>()
    for (run in 0..args.runs) {
        val home = makeHome(root, scenario.db)
        val metrics = scenario.measure(home, VARIANT_FLAGS[variant].orEmpty())
        home.deleteRecursively()
        if (run == 0) continue
        for ((metric, ms) in metrics) samples.getOrPut(metric) { mutableListOf() } += ms
    }
    return samples
}

fun startupBench(argv: List<String>) {
    val args = parseArgs(argv)
    val date = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC).format(Instant.now())
    val root = Files.createTempDirectory("mm-startup-bench-").toFile()
    val lines = StringBuilder()
    try {
        for (scenario in scenarios(args)) {
            for (variant in scenario.variants) {
                for ((metric, values) in sample(args, root, scenario, variant)) {
                    lines.append(Json.encodeToString(record(args, date, scenario, variant, metric, values))).append('\n')
                    System.err.println(
                        "%-8s %-20s %-8s %-18s median %8.0f ms  (min %.0f, max %.0f)".format(
                            scenario.runtime,
                            scenario.name,
                            variant,
                            metric,
                            round1(median(values)),
                            values.min(),
                            values.max(),
                        ),
                    )
                }
            }
        }
    } finally {
        root.deleteRecursively()
    }
    if (args.out != null) File(args.out).appendText(lines.toString()) else print(lines)
}
