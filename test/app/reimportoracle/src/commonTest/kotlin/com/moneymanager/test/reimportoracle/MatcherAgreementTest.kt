package com.moneymanager.test.reimportoracle

import com.moneymanager.csvimporter.matchFamily
import com.moneymanager.importer.MatchScopeLoader
import com.moneymanager.observationmatcher.AssignmentChange
import com.moneymanager.observationmatcher.TransactionTarget
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.minutes

/**
 * The observation matcher, run over what a fresh import of each scenario stored, keeps every observation on the
 * transaction it already observes, but for one difference the switch (#1428) makes on purpose: two records today's
 * deduper books as two transactions and reconciles (one excluded, linked to the other) are one transaction to the
 * matcher. So each change it makes must join two transactions a relationship already links.
 */
class MatcherAgreementTest {
    @Test
    fun the_matcher_keeps_every_observation_where_a_fresh_import_put_it_but_joins_reconciled_twins() =
        runTest(timeout = 10.minutes) {
            val problems = mutableListOf<String>()
            var twins = 0
            for (scenario in OracleCorpus.ALL) {
                OracleDatabase.with(scenario) {
                    import()
                    val outcome = matchStored()
                    twins += outcome.twins
                    if (outcome.disagreement.isNotEmpty()) problems += "${scenario.name}:\n${outcome.disagreement}"
                }
            }
            if (problems.isNotEmpty()) fail(problems.joinToString("\n\n"))
            assertTrue(twins > 0, "no scenario has reconciled twins, so the test would hold vacuously")
        }

    private class Outcome(
        val twins: Int,
        val disagreement: String,
    )

    private suspend fun OracleDatabase.matchStored(): Outcome {
        val attributeTypes =
            db.attributeTypeRepository
                .getAll()
                .first()
                .associate { it.name to it.id }
        val families =
            db.csvImportStrategyRepository
                .getAllStrategies()
                .first()
                .associate { it.id.toString() to it.matchFamily(attributeTypes) }
        val loader =
            MatchScopeLoader(
                db.observationRepository,
                db.importRecordRepository,
                db.csvImportRepository,
                db.apiSessionRepository,
                db.accountAttributeRepository,
                db.attributeTypeRepository,
            )
        val accounts =
            db.accountRepository
                .getAllAccounts()
                .first()
                .map { it.id }
        val scope = loader.load(accounts, families)
        val result = MatchScopeLoader.matcherFor(families, MatchScopeLoader.containerAccounts(scope)).match(scope)
        val (twins, others) = result.changes.partition { isReconciledTwin(it) }
        val byRef = scope.associateBy { it.ref }
        val disagreement =
            buildString {
                for (change in others) {
                    val o = byRef.getValue(change.ref)
                    append("  ${o.container} ${change.ref.slot} ${o.content.values}: ${change.from} -> ${change.to}\n")
                }
                for (exclusion in result.exclusions) append("  exclusion $exclusion\n")
            }
        return Outcome(twins.size, disagreement)
    }

    /** True when [change] moves an observation onto a transaction its own is already linked to. */
    private suspend fun OracleDatabase.isReconciledTwin(change: AssignmentChange): Boolean {
        val from = change.from ?: return false
        val to = (change.to as? TransactionTarget.Existing)?.id ?: return false
        return db.relationshipRepository
            .getByTransactions(listOf(from))
            .any { relationship -> relationship.members.any { it.transactionId == to } }
    }
}
