package com.moneymanager.test.reimportoracle

import com.moneymanager.domain.model.DbLocation
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.random.Random
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.measureTime

/**
 * Times staged imports and re-imports on a large database (#1433): a copy of `//tools/bench-db`'s 20,000 transfers,
 * then [FILES] pairs of Monzo personal and joint exports of [ROWS] rows each, one in [TRANSFER_EVERY] a transfer
 * between the two accounts that both files record (twins the matcher joins).
 *
 * It's a benchmark, not a check, so it runs only when asked:
 * `bazel test --config=rbe //test/app/reimportoracle:test_jvm --test_env=SYNC_BENCH=1 --test_filter=SyncBenchmarkTest
 * --test_timeout=7200 --test_output=streamed`
 * (`SYNC_BENCH_FILES` and `SYNC_BENCH_ROWS` change the size).
 */
class SyncBenchmarkTest {
    @Test
    fun benchmark() {
        if (System.getenv("SYNC_BENCH") == null) return
        val files = System.getenv("SYNC_BENCH_FILES")?.toInt() ?: FILES
        val rows = System.getenv("SYNC_BENCH_ROWS")?.toInt() ?: ROWS
        runTest(timeout = 2.hours) {
            val scenario = scenario(files, rows)
            OracleDatabase.with(scenario, benchDatabaseCopy()) {
                val imports = scenario.sources.map { source -> measureTime { import(listOf(source)) } }
                report("import, first file", imports.first())
                report("import, last file", imports.last())
                report("import, all ${imports.size} files", imports.fold(Duration.ZERO, Duration::plus))
                report("re-import one file, unchanged", measureTime { reimport(listOf(scenario.sources.last())) })
                report("re-import all, unchanged", measureTime { reimportAll() })
                changeStrategy()
                report("re-import all, strategy changed", measureTime { reimportAll() })
                report("re-import all, unchanged again", measureTime { reimportAll() })
            }
        }
    }

    private fun report(
        step: String,
        time: Duration,
    ) = println("SYNC_BENCH | $step | ${time.inWholeMilliseconds} ms")

    /** A copy of the benchmark database, which the run then changes and deletes. */
    private fun benchDatabaseCopy(): DbLocation {
        val bench =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { it.resolve("tools/bench-db/bench.db") }
                .first { it.exists() }
        val copy = Files.createTempDirectory("sync-bench").resolve("bench.db")
        Files.copy(bench.toPath(), copy, StandardCopyOption.REPLACE_EXISTING)
        return DbLocation(copy.toString())
    }

    private fun scenario(
        files: Int,
        rows: Int,
    ): OracleScenario {
        val random = Random(SEED)
        val sources =
            (0 until files).flatMap { file ->
                val personal = mutableListOf<List<String>>()
                val joint = mutableListOf<List<String>>()
                repeat(rows) { row ->
                    val day = file * rows + row
                    val date = "%02d/%02d/%04d".format(day % 28 + 1, day / 28 % 12 + 1, 2010 + day / (28 * 12))
                    val time = "%02d:%02d:00".format(row / 60 % 24, row % 60)
                    val pence = random.nextInt(100, 100_000)
                    val amount = "${pence / 100}.%02d".format(pence % 100)
                    if (row % TRANSFER_EVERY == 0) {
                        personal += monzoRow("tx_p_${file}_$row", date, time, "Monzo Joint", "-$amount", "To joint")
                        joint += monzoRow("tx_j_${file}_$row", date, time, "Monzo", amount, "From personal")
                    } else {
                        val merchant = MERCHANTS[random.nextInt(MERCHANTS.size)]
                        personal += monzoRow("tx_p_${file}_$row", date, time, merchant, "-$amount", merchant.uppercase())
                    }
                }
                listOf(
                    OracleSource(monzoFile("MonzoDataExport_personal_$file.csv", *personal.toTypedArray()), account = "Monzo"),
                    OracleSource(monzoFile("MonzoDataExport_joint_$file.csv", *joint.toTypedArray()), account = "Monzo Joint"),
                )
            }
        return OracleScenario(name = "sync benchmark", sources = sources, changedStrategy = "Monzo CSV")
    }

    private companion object {
        const val SEED = 1433
        const val FILES = 4
        const val ROWS = 1_000
        const val TRANSFER_EVERY = 5
        val MERCHANTS = listOf("Tesco", "Sainsbury's", "Pret", "TfL", "Landlord", "Netflix", "Boots", "Amazon")
    }
}
