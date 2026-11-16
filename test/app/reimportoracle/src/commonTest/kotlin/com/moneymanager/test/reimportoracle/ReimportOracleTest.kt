package com.moneymanager.test.reimportoracle

import app.cash.sqldelight.async.coroutines.awaitAsList
import com.moneymanager.test.database.read.testReads
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration.Companion.minutes

/**
 * The convergence oracle (#1420): whatever order files are imported or re-imported in, and whatever hand work a person
 * did in between, the database must end up holding what a fresh import of the same files holds.
 *
 * Each test runs one assertion over every scenario of [OracleCorpus] and compares the outcome with [BASELINE], the cases
 * that fail today and the epic #1419 subtask that should fix each. A case in the baseline that starts passing fails
 * the test too, so the fix that makes it pass also removes its line.
 */
class ReimportOracleTest {
    @Test
    fun a_fresh_import_is_the_same_in_every_file_order() =
        oracle(Assertion.FILE_ORDER) { scenario ->
            val expected = OracleDatabase.with(scenario) { importAndSnapshot() }
            permutations(scenario).firstNotNullOfOrNull { order ->
                val actual = OracleDatabase.with(scenario) { importAndSnapshot(order) }
                difference(expected, actual, "import order ${order.names()}")
            }
        }

    @Test
    fun b_reimporting_every_source_in_any_order_changes_nothing() =
        oracle(Assertion.REIMPORT) { scenario ->
            permutations(scenario).firstNotNullOfOrNull { order ->
                OracleDatabase.with(scenario) {
                    val expected = importAndSnapshot()
                    reimport(order)
                    difference(expected, db.canonicalSnapshot(), "re-import order ${order.names()}")
                }
            }
        }

    @Test
    fun c_reimporting_after_a_strategy_change_matches_a_fresh_import_with_the_new_strategy() =
        oracle(Assertion.STRATEGY_CHANGE) { scenario ->
            val expected =
                OracleDatabase
                    .with(scenario) {
                        changeStrategy()
                        importAndSnapshot()
                    }.also { it.expectStrategyChange() }
            permutations(scenario).firstNotNullOfOrNull { order ->
                OracleDatabase.with(scenario) {
                    import()
                    changeStrategy()
                    reimport(order)
                    difference(expected, db.canonicalSnapshot(), "re-import order ${order.names()}")
                }
            }
        }

    @Test
    fun d_hand_work_survives_a_reimport() =
        oracle(Assertion.HAND_WORK_REIMPORT) { scenario ->
            permutations(scenario).firstNotNullOfOrNull { order ->
                OracleDatabase.with(scenario) {
                    import()
                    doHandWork()
                    val expected = db.canonicalSnapshot().also { it.expectHandWork(scenario) }
                    reimport(order)
                    difference(expected, db.canonicalSnapshot(), "re-import order ${order.names()}")
                }
            }
        }

    @Test
    fun d_hand_work_survives_a_strategy_change() =
        oracle(Assertion.HAND_WORK_STRATEGY_CHANGE) { scenario ->
            val expected =
                OracleDatabase
                    .with(scenario) {
                        changeStrategy()
                        import()
                        doHandWork()
                        db.canonicalSnapshot()
                    }.also {
                        it.expectStrategyChange()
                        it.expectHandWork(scenario)
                    }
            permutations(scenario).firstNotNullOfOrNull { order ->
                OracleDatabase.with(scenario) {
                    import()
                    doHandWork()
                    changeStrategy()
                    reimport(order)
                    difference(expected, db.canonicalSnapshot(), "re-import order ${order.names()}")
                }
            }
        }

    @Test
    fun e_reimporting_an_unchanged_source_writes_no_audit_rows() =
        oracle(Assertion.NO_AUDIT_WRITES) { scenario ->
            OracleDatabase.with(scenario) {
                import()
                scenario.sources.firstNotNullOfOrNull { source ->
                    val before = auditCounts()
                    reimport(listOf(source))
                    val after = auditCounts()
                    val grown = after.filter { (table, count) -> count != before[table] }
                    if (grown.isEmpty()) {
                        null
                    } else {
                        "re-importing ${source.file.fileName} wrote audit rows: " +
                            grown.entries.joinToString { (table, count) -> "$table ${before[table]} -> $count" }
                    }
                }
            }
        }

    /**
     * Unimport is a sync of the file with no records: unimporting any one source leaves what a fresh import of the others
     * holds. Unimporting one of two exports of one movement (Monzo's twins) keeps it, counted from the other export.
     */
    @Test
    fun f_unimporting_a_source_leaves_what_a_fresh_import_of_the_others_holds() =
        oracle(Assertion.UNIMPORT) { scenario ->
            scenario.sources.firstNotNullOfOrNull { source ->
                val others = scenario.sources - source
                val expected = OracleDatabase.with(scenario) { importAndSnapshot(others) }
                OracleDatabase.with(scenario) {
                    import()
                    unimport(source)
                    difference(expected, db.canonicalSnapshot(), "unimporting ${source.file.fileName}")
                }
            }
        }

    /** Fails unless the strategy change of (c) reached the database, so (c) can never hold vacuously. */
    private fun CanonicalSnapshot.expectStrategyChange() =
        check((transfers + trades).any { "$ORACLE_STRATEGY_ATTRIBUTE=v2" in it }) { "the strategy change tagged nothing" }

    /** Fails unless every piece of (d)'s hand work reached the database, so (d) can never hold vacuously. */
    private fun CanonicalSnapshot.expectHandWork(scenario: OracleScenario) {
        check(transfers.any { "$ORACLE_NOTE_TYPE=$ORACLE_NOTE" in it }) { "the hand edit is missing" }
        check(relationships.any { it.startsWith("reconciliation-match:") }) { "the hand-made match is missing" }
        for (entry in scenario.manualEntries) {
            check(transfers.any { "| ${entry.description}" in it }) { "the ${entry.name} manual entry is missing" }
        }
    }

    /** Every audit table's row count, except the one that records the re-import run itself. */
    private suspend fun OracleDatabase.auditCounts(): Map<String, Long> =
        database.testReads.rowCountSelectQueries
            .countAuditRowsByTable()
            .awaitAsList()
            .filter { it.table_name != "csv_import_application_audit" }
            .associate { it.table_name.orEmpty() to it.row_count }

    private suspend fun OracleDatabase.importAndSnapshot(order: List<OracleSource> = scenario.sources): CanonicalSnapshot {
        import(order)
        return db.canonicalSnapshot()
    }

    private fun difference(
        expected: CanonicalSnapshot,
        actual: CanonicalSnapshot,
        context: String,
    ): String? {
        if (expected == actual) return null
        val expectedLines = expected.render().lines()
        val actualLines = actual.render().lines()
        val missing = expectedLines - actualLines.toSet()
        val extra = actualLines - expectedLines.toSet()
        return buildString {
            append(context).append(" differs:\n")
            missing.forEach { append("  - ").append(it).append('\n') }
            extra.forEach { append("  + ").append(it).append('\n') }
            if (missing.isEmpty() && extra.isEmpty()) append("  (same lines, different counts)\n")
        }
    }

    private fun permutations(scenario: OracleScenario): List<List<OracleSource>> =
        (1..PERMUTATIONS).map { seed -> scenario.sources.shuffled(Random(seed)) }.distinct()

    private fun List<OracleSource>.names(): String = joinToString(prefix = "[", postfix = "]") { it.file.fileName }

    /**
     * Runs [check] for every scenario (null = it holds) and fails unless the outcomes are exactly [BASELINE]'s for
     * [assertion]: an unexpected failure with its difference, an expected one that now holds with the line to remove.
     */
    private fun oracle(
        assertion: Assertion,
        check: suspend (OracleScenario) -> String?,
    ) = runTest(timeout = 30.minutes) {
        val problems = mutableListOf<String>()
        for (scenario in OracleCorpus.ALL) {
            val failure =
                try {
                    check(scenario)
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    "threw ${e::class.simpleName}: ${e.message}"
                }
            val issue = BASELINE[scenario.name to assertion]
            when {
                failure != null && issue == null -> {
                    problems += "${scenario.name}: $failure"
                }

                failure == null && issue != null -> {
                    problems += "${scenario.name} now holds: remove its $assertion line (#$issue) from BASELINE"
                }
            }
        }
        if (problems.isNotEmpty()) fail("$assertion\n" + problems.joinToString("\n\n"))
    }

    private companion object {
        const val PERMUTATIONS = 3
    }
}

enum class Assertion {
    FILE_ORDER,
    REIMPORT,
    STRATEGY_CHANGE,
    HAND_WORK_REIMPORT,
    HAND_WORK_STRATEGY_CHANGE,
    NO_AUDIT_WRITES,
    UNIMPORT,
}

/**
 * The cases that fail today, by (scenario, assertion), each with the epic #1419 subtask that should fix it. Empty since
 * staged files derive, match and materialize (#1428): every scenario converges.
 */
val BASELINE: Map<Pair<String, Assertion>, Int> = emptyMap()
