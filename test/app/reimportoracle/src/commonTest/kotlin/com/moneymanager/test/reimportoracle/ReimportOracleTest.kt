package com.moneymanager.test.reimportoracle

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.test.database.read.testReads
import kotlinx.coroutines.flow.first
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
                    val handled = doHandWork()
                    val expected = db.canonicalSnapshot().also { it.expectHandWork(scenario) }
                    reimport(order)
                    lostIds(handled, "re-import order ${order.names()}")
                        ?: difference(expected, db.canonicalSnapshot(), "re-import order ${order.names()}")
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
                    val handled = doHandWork()
                    changeStrategy()
                    reimport(order)
                    lostIds(handled, "re-import order ${order.names()}")
                        ?: difference(expected, db.canonicalSnapshot(), "re-import order ${order.names()}")
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

    /**
     * A forced re-run of one unchanged source changes nothing, whichever source it is. Unlike (b), nothing is skipped
     * for being unchanged, so a source whose records others reconciled against is torn down and booked again while the
     * others stay: what they reconciled must come back as it was.
     */
    @Test
    fun g_forcing_a_reimport_of_any_one_source_changes_nothing() =
        oracle(Assertion.FORCED_REIMPORT) { scenario ->
            scenario.sources.firstNotNullOfOrNull { source ->
                OracleDatabase.with(scenario) {
                    val expected = importAndSnapshot()
                    reimport(listOf(source), force = true)
                    difference(expected, db.canonicalSnapshot(), "forced re-import of ${source.file.fileName}")
                }
            }
        }

    /**
     * A forced re-run of one unchanged source writes nothing (#1510): its transactions stay standing through the re-run, and
     * each record that says what it said before takes its own back without a write.
     */
    @Test
    fun g_forcing_a_reimport_of_an_unchanged_source_writes_no_audit_rows() =
        oracle(Assertion.FORCED_NO_AUDIT_WRITES) { scenario ->
            OracleDatabase.with(scenario) {
                import()
                scenario.sources.firstNotNullOfOrNull { source ->
                    val before = auditCounts()
                    val counts = database.testReads.rowCountSelectQueries
                    val accountAuditBefore = counts.maxAccountAuditId().awaitAsOne()
                    reimport(listOf(source), force = true)
                    // A CSV file creates every account its rows name before it pairs rows into trades, so a suspense account
                    // ("PayPal Conversions") nothing ends up using is created and removed again, by a first import too. Only an
                    // account that existed before the run and was changed or deleted by it counts here.
                    val accountsChanged = counts.selectPreexistingAccountsAuditedAfter(accountAuditBefore).awaitAsList().isNotEmpty()
                    val grown =
                        auditCounts().filter { (table, count) -> count != before[table] && (table != "account_audit" || accountsChanged) }
                    if (grown.isEmpty()) {
                        null
                    } else {
                        "forcing a re-import of ${source.file.fileName} wrote audit rows: " +
                            grown.entries.joinToString { (table, count) -> "$table ${before[table]} -> $count" }
                    }
                }
            }
        }

    /**
     * Copy to Money Manager is what the source row's own record says on the real accounts (#1475): re-importing the
     * source with the row unchanged changes nothing (and, unforced, writes nothing), forced or not.
     */
    @Test
    fun g_forcing_a_bulk_reimport_of_csv_files_changes_nothing() =
        oracle(Assertion.FORCED_BULK_CSV_REIMPORT) { scenario ->
            val sources = scenario.sources.filterNot { it.file is OracleFile.Api }
            if (sources.size < 2) return@oracle null
            OracleDatabase.with(scenario) {
                val expected = importAndSnapshot()
                reimportAll(sources, force = true)
                difference(expected, db.canonicalSnapshot(), "forced bulk re-import of ${sources.names()}")
            }
        }

    @Test
    fun h_a_copy_survives_reimporting_its_unchanged_source() =
        oracle(Assertion.COPY_UNCHANGED) { scenario ->
            if (scenario.copiedRow == null) return@oracle null
            OracleDatabase.with(scenario) {
                import()
                expectCopies(copySourceTrade())
                val expected = db.canonicalSnapshot()
                val source = copiedRowSource()
                val before = auditCounts()
                reimport(listOf(source))
                val grown = auditCounts().filter { (table, count) -> count != before[table] }
                if (grown.isNotEmpty()) return@with "re-importing ${source.file.fileName} unchanged wrote audit rows: ${grown.keys}"
                difference(expected, db.canonicalSnapshot(), "re-import of ${source.file.fileName}")
                    ?: run {
                        reimport(listOf(source), force = true)
                        difference(expected, db.canonicalSnapshot(), "forced re-import of ${source.file.fileName}")
                    }
            }
        }

    /** A copied row whose amount changed at its source: the re-import updates the copy, as a fresh import and copy would book it. */
    @Test
    fun h_a_copy_follows_its_changed_row() =
        oracle(Assertion.COPY_CHANGED) { scenario ->
            if (scenario.copiedRow == null) return@oracle null
            val expected =
                OracleDatabase.with(scenario) {
                    changeCopiedRow()
                    import()
                    expectCopies(copySourceTrade())
                    db.canonicalSnapshot()
                }
            OracleDatabase.with(scenario) {
                import()
                val copies = expectCopies(copySourceTrade())
                expectChange(expected)
                changeCopiedRow()
                reimport(listOf(copiedRowSource()))
                lostCopies(copies) ?: difference(expected, db.canonicalSnapshot(), "re-import after the copied row changed")
            }
        }

    /** A copied row its source no longer reports: the re-import removes the copy, leaving the wallets linked and nothing copied. */
    @Test
    fun h_a_copy_goes_with_its_deleted_row() =
        oracle(Assertion.COPY_DELETED) { scenario ->
            if (scenario.copiedRow == null) return@oracle null
            val expected =
                OracleDatabase.with(scenario) {
                    changeCopiedRow(drops = true)
                    import()
                    linkWallets()
                    db.canonicalSnapshot()
                }
            OracleDatabase.with(scenario) {
                import()
                expectCopies(copySourceTrade())
                expectChange(expected)
                changeCopiedRow(drops = true)
                reimport(listOf(copiedRowSource()))
                difference(expected, db.canonicalSnapshot(), "re-import after the copied row was deleted")
            }
        }

    /** A copy a person edited, whose row then changed: the copy keeps its id and the person's note, and takes the new amount. */
    @Test
    fun h_a_hand_edited_copy_follows_its_changed_row() =
        oracle(Assertion.COPY_HAND_EDITED) { scenario ->
            if (scenario.copiedRow == null) return@oracle null
            val expected =
                OracleDatabase
                    .with(scenario) {
                        changeCopiedRow()
                        import()
                        noteOnCopies(expectCopies(copySourceTrade()))
                        db.canonicalSnapshot()
                    }.also { snapshot -> check(snapshot.trades.any { "$ORACLE_NOTE_TYPE=$ORACLE_NOTE" in it }) { "the note is missing" } }
            OracleDatabase.with(scenario) {
                import()
                val copies = expectCopies(copySourceTrade())
                noteOnCopies(copies)
                expectChange(expected)
                changeCopiedRow()
                reimport(listOf(copiedRowSource()))
                lostCopies(copies) ?: difference(expected, db.canonicalSnapshot(), "re-import after the copied row changed")
            }
        }

    private fun expectCopies(copies: List<TransactionId>): List<TransactionId> =
        copies.also { check(it.isNotEmpty()) { "nothing was copied" } }

    /** Fails unless the database differs from [changed], so the change of the copied row can never hold vacuously. */
    private suspend fun OracleDatabase.expectChange(changed: CanonicalSnapshot) =
        check(db.canonicalSnapshot() != changed) { "the change of the copied row reached nothing" }

    /** Which of [copies] a re-import gave a new id rather than keeping: the match and a person's edits hang off the old one. */
    private suspend fun OracleDatabase.lostCopies(copies: List<TransactionId>): String? =
        lostIds(HandWork(transfers = emptySet(), trades = emptySet(), copies = copies), "the re-import")

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
        check(trades.isEmpty() || trades.any { ORACLE_TRADE_EXCLUSION in it }) { "the hand exclusion of a trade is missing" }
    }

    /** Which of the hand-worked records a re-import gave a new id rather than keeping (#1430): a person's work hangs off the old one. */
    private suspend fun OracleDatabase.lostIds(
        handled: HandWork,
        context: String,
    ): String? {
        val transfers = handled.transfers + handled.copies.filterIsInstance<TransferId>()
        val trades = handled.trades + handled.copies.filterIsInstance<TradeId>()
        val keptTransfers = db.transactionRepository.getTransactionsByIds(transfers.toList()).keys
        val keptTrades =
            db.accountRepository
                .getAllAccounts()
                .first()
                .flatMap { db.tradeRepository.getTradesByAccount(it.id).first() }
                .mapTo(mutableSetOf()) { it.id }
        val keptPins = db.personOverrideRepository.getAllPins().toSet()
        val keptSplits = db.personOverrideRepository.getAllSplits()
        val lost =
            (transfers - keptTransfers).map { "transfer ${it.id}" } + (trades - keptTrades).map { "trade ${it.id}" } +
                (handled.pins - keptPins).map { "pin of observation ${it.observationId.id}" } +
                (handled.splits - keptSplits).map { "split of observations ${it.first.id} and ${it.second.id}" }
        return if (lost.isEmpty()) null else "$context lost the ids of hand-worked records ${lost.sorted()}"
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
    FORCED_REIMPORT,
    FORCED_NO_AUDIT_WRITES,
    FORCED_BULK_CSV_REIMPORT,
    COPY_UNCHANGED,
    COPY_CHANGED,
    COPY_DELETED,
    COPY_HAND_EDITED,
}

/**
 * The cases that fail today, by (scenario, assertion), each with the epic #1419 subtask that should fix it. Empty since
 * staged files derive, match and materialize (#1428): every scenario converges.
 */
val BASELINE: Map<Pair<String, Assertion>, Int> = emptyMap()
