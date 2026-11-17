package com.moneymanager.test.reimportoracle

import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.MaterializationRequest
import com.moneymanager.importengineapi.matchFamily
import com.moneymanager.importer.MatchScopeLoader
import com.moneymanager.observationmatcher.ExclusionLink
import com.moneymanager.observationmatcher.MatchObservation
import com.moneymanager.observationmatcher.ObservationRef
import com.moneymanager.observationmatcher.ShapeReason
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.minutes

/**
 * Staged files derive, match and materialize (#1428), so what a fresh import stores is already the observation
 * matcher's answer: run again over it, the matcher moves nothing. Two records of one event (a bank's and a card's
 * line of one payment) are one transaction carrying both observations.
 */
class MatcherAgreementTest {
    @Test
    fun the_matcher_moves_nothing_a_fresh_import_stored() =
        runTest(timeout = 10.minutes) {
            val problems = mutableListOf<String>()
            var twins = 0
            for (scenario in OracleCorpus.ALL) {
                OracleDatabase.with(scenario) {
                    import()
                    val outcome = matchStored()
                    twins += joinedTwins()
                    if (outcome.isNotEmpty()) problems += "${scenario.name}:\n$outcome"
                }
            }
            if (problems.isNotEmpty()) fail(problems.joinToString("\n\n"))
            assertTrue(twins > 0, "no scenario joins two records into one transaction, so the test would hold vacuously")
        }

    @Test
    fun materializing_a_fresh_import_writes_nothing() =
        runTest(timeout = 10.minutes) {
            val problems = mutableListOf<String>()
            for (scenario in OracleCorpus.ALL) {
                OracleDatabase.with(scenario) {
                    import()
                    val before = db.canonicalSnapshot().render().lines()
                    val accounts =
                        db.accountRepository
                            .getAllAccounts()
                            .first()
                            .map { it.id }
                    val ids =
                        db.observationRepository
                            .getObservationsOnAccounts(accounts)
                            .mapTo(mutableSetOf()) { it.transactionId }
                    val report =
                        db.importEngine
                            .import(
                                ImportBatch(
                                    transfers = emptyList(),
                                    materialization = MaterializationRequest(Source.SampleGenerator, ids),
                                ),
                            ).materialization
                    if (report.inserted.isNotEmpty() || report.updated.isNotEmpty() || report.deleted.isNotEmpty()) {
                        val after = db.canonicalSnapshot().render().lines()
                        problems +=
                            "${scenario.name}: inserted ${report.inserted}, updated ${report.updated}, deleted ${report.deleted}\n" +
                            (before - after.toSet()).joinToString("") { "  - $it\n" } +
                            (after - before.toSet()).joinToString("") { "  + $it\n" }
                    }
                }
            }
            if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
        }

    /** What the matcher would change in what is stored, one line each; empty when it agrees. */
    private suspend fun OracleDatabase.matchStored(): String {
        val attributeTypes =
            db.attributeTypeRepository
                .getAll()
                .first()
                .associate { it.name to it.id }
        val allAccounts = db.accountRepository.getAllAccounts().first()
        val accountIdByName = allAccounts.reversed().associate { it.name to it.id }
        val families =
            db.csvImportStrategyRepository
                .getAllStrategies()
                .first()
                .associate { MatchScopeLoader.csvFamily(it.id.toString()) to it.matchFamily(attributeTypes) } +
                db.apiImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .associate { MatchScopeLoader.apiFamily(it.id.toString()) to it.matchFamily(attributeTypes, accountIdByName) }
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
        // As the import runs it: a shape match (a placeholder's other side, a conduit's chain) keeps its records apart.
        val involved: Set<ObservationRef> = MatchScopeLoader.shapeMatcherFor(families).match(scope).involved
        val result =
            MatchScopeLoader
                .matcherFor(families, MatchScopeLoader.containerAccounts(scope))
                .match(scope.filter { it.ref !in involved })
        val byRef = scope.associateBy { it.ref }
        return buildString {
            for (change in result.changes) {
                val o = byRef.getValue(change.ref)
                append("  ${o.container} ${change.ref.slot} ${o.content.values}: ${change.from} -> ${change.to}\n")
            }
            for (exclusion in result.exclusions) {
                if (!stored(exclusion, byRef)) append("  exclusion $exclusion\n")
            }
        }
    }

    /**
     * Whether a fresh import already stored [exclusion]: a `reconciled` relationship over all its transactions, its
     * excluded ones out of balances. The import writes the trade-aggregation link itself (#1429).
     */
    private suspend fun OracleDatabase.stored(
        exclusion: ExclusionLink,
        byRef: Map<ObservationRef, MatchObservation>,
    ): Boolean {
        if (exclusion.reason != ShapeReason.TRADE_AGGREGATION) return false
        val ids = (exclusion.matched + exclusion.excluded).mapNotNull { byRef[it]?.current }.toSet()
        return db.relationshipRepository.getByTransactions(ids).any { relationship ->
            relationship.relationshipType.id.id == WellKnownIds.RECONCILED_RELATIONSHIP_TYPE_ID &&
                relationship.members.map { it.transactionId.id }.containsAll(ids.map { it.id })
        }
    }

    /** Transactions that carry observations of more than one record: twins the matcher joined. */
    private suspend fun OracleDatabase.joinedTwins(): Int {
        val accounts =
            db.accountRepository
                .getAllAccounts()
                .first()
                .map { it.id }
        return db.observationRepository
            .getObservationsOnAccounts(accounts)
            .groupBy { it.transactionId }
            .count { (_, observations) -> observations.map { it.owner }.distinct().size > 1 }
    }
}
