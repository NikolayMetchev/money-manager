package com.moneymanager.importer

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.RelationshipMember
import com.moneymanager.domain.model.RelationshipRole
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.SourceType
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.calendar.BusinessDayCalendar
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.observation.ImportRecord
import com.moneymanager.domain.model.observation.ImportRecordId
import com.moneymanager.domain.model.observation.ImportRecordKey
import com.moneymanager.domain.model.observation.ObservationChange
import com.moneymanager.domain.model.observation.ObservationOwner
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.ApiImportStrategyReadRepository
import com.moneymanager.domain.repository.AttributeTypeReadRepository
import com.moneymanager.domain.repository.CsvImportStrategyReadRepository
import com.moneymanager.domain.repository.ImportRecordReadRepository
import com.moneymanager.domain.repository.PersonOverrideReadRepository
import com.moneymanager.domain.repository.write.CsvImportWriteRepository
import com.moneymanager.domain.repository.write.RelationshipWrite
import com.moneymanager.domain.repository.write.RelationshipWriteRepository
import com.moneymanager.importengineapi.LocalTransactionKey
import com.moneymanager.importengineapi.MaterializationReport
import com.moneymanager.importengineapi.MaterializationRequest
import com.moneymanager.importengineapi.ObservationMutation
import com.moneymanager.importengineapi.ObservationSlot
import com.moneymanager.importengineapi.ObservationTransaction
import com.moneymanager.importengineapi.matchFamily
import com.moneymanager.observationmatcher.AssignmentChange
import com.moneymanager.observationmatcher.ExclusionLink
import com.moneymanager.observationmatcher.MatchObservation
import com.moneymanager.observationmatcher.MatchResult
import com.moneymanager.observationmatcher.ObservationRef
import com.moneymanager.observationmatcher.ShapeReason
import com.moneymanager.observationmatcher.TransactionTarget
import kotlinx.coroutines.flow.first

/**
 * Re-matches every stored observation with the observation matcher and moves those it assigns elsewhere (#1428):
 * two records today's dedupe booked as a reconciled pair (one counted, one excluded and linked) become one
 * transaction carrying both observations, rank-merged by the materializer.
 *
 * The transaction that loses its last observation goes. Its other relationships move to the one that stays, and the
 * staged rows that observed it are linked to that one as duplicates, so a re-import of either file still finds the
 * other through provenance.
 */
internal class MatchSync(
    private val loader: MatchScopeLoader,
    private val strategies: CsvImportStrategyReadRepository,
    private val apiStrategies: ApiImportStrategyReadRepository,
    private val attributeTypes: AttributeTypeReadRepository,
    private val accounts: AccountReadRepository,
    private val records: ImportRecordReadRepository,
    private val csvImports: CsvImportWriteRepository,
    private val relationships: RelationshipWriteRepository?,
    private val overrides: PersonOverrideReadRepository?,
    private val calendarFor: (List<AccountId>) -> BusinessDayCalendar,
    private val materialize: suspend (List<ObservationMutation>, MaterializationRequest) -> MaterializationReport,
) {
    suspend fun run(): MaterializationReport {
        val plan = plan()
        if (!plan.hasChanges) return MaterializationReport()
        if (plan.links.writes.isNotEmpty()) relationships?.apply(plan.links.writes)
        val result = plan.result
        val contentOf = plan.scope.associate { it.ref to it.content }
        val mutations =
            result.changes.map { change ->
                ObservationMutation.Upsert(change.ref.owner, change.ref.slot, change.to.asTransaction(), contentOf.getValue(change.ref))
            }
        // Where each released transaction's observations went; a split sends them to several, and then nothing moves.
        val destinations =
            result.changes
                .filter { it.from in result.released }
                .groupBy({ requireNotNull(it.from) }, { it.to })
                .mapValues { (_, targets) -> targets.distinct().singleOrNull() }

        val known = destinations.mapNotNull { (from, to) -> (to as? TransactionTarget.Existing)?.let { from to it.id } }.toMap()
        moveRelationships(known)
        val report = materialize(mutations, MaterializationRequest(Source.Reconciler, plan.links.affected))
        val resolved = known + destinations.mapNotNull { (from, to) -> newIdOf(to, report)?.let { from to it } }
        relinkRows(result.changes, resolved)
        return report
    }

    internal suspend fun needsWrites(
        proposed: List<ObservationChange>,
        newRecords: List<ImportRecord>,
        recordHashes: Map<ImportRecordId, String>,
        csvFamilies: Map<CsvImportId, String>,
    ): Boolean = plan(proposed, newRecords, recordHashes, csvFamilies).hasChanges

    private suspend fun plan(
        proposed: List<ObservationChange> = emptyList(),
        newRecords: List<ImportRecord> = emptyList(),
        recordHashes: Map<ImportRecordId, String> = emptyMap(),
        csvFamilies: Map<CsvImportId, String> = emptyMap(),
    ): MatchPlan {
        val typeIds = attributeTypes.getAll().first().associate { it.name to it.id }
        val allAccounts = accounts.getAllAccounts().first()
        // An account name can repeat; a bridge names the first account of that name, as the importer picks it.
        val accountIdByName = allAccounts.reversed().associate { it.name to it.id }
        val families =
            strategies.getAllStrategies().first().associate { MatchScopeLoader.csvFamily(it.id.toString()) to it.matchFamily(typeIds) } +
                apiStrategies.getAllStrategies().first().associate {
                    MatchScopeLoader.apiFamily(it.id.toString()) to
                        it.matchFamily(typeIds, accountIdByName)
                }
        val loaded = loader.load(allAccounts.map { it.id }, families, proposed, newRecords, recordHashes, csvFamilies)
        // A record no strategy here describes (a file or session whose strategy is gone) has no rules to match it
        // by, so the matcher would split whatever its import joined it with: its transaction stays as the import left it.
        val frozen =
            loaded
                .filter { it.family !in families && it.family != MatchScopeLoader.MANUAL_FAMILY }
                .mapNotNullTo(mutableSetOf()) { it.current }
        val scope = loaded.filter { it.current !in frozen && (it.current != null || it.family in families) }
        // A person's pins and splits (#1430) are given: a pinned observation is never shape-linked away from its
        // transaction, and no link holds two observations a person split.
        val pins = overrides?.getAllPins().orEmpty()
        val splits = overrides?.getAllSplits().orEmpty()
        val pinned = pins.mapTo(mutableSetOf()) { it.observationId }
        val refOf = scope.mapNotNull { o -> o.storedId?.let { it to o.ref } }.toMap()
        val apart = splits.mapNotNull { s -> refOf[s.first]?.let { a -> refOf[s.second]?.let { b -> a to b } } }
        val links =
            MatchScopeLoader
                .shapeMatcherFor(families, calendarFor)
                .match(scope.filter { it.storedId !in pinned })
                .links
                .filterNot { it.holdsApart(apart) }
        val involved = links.flatMapTo(mutableSetOf()) { it.matched + it.excluded }
        val twins = scope.filter { it.ref !in involved }
        val result =
            MatchScopeLoader
                .matcherFor(
                    families,
                    MatchScopeLoader.containerAccounts(scope),
                    calendarFor,
                    stageReasons = WRITTEN_STAGE_REASONS,
                ).match(twins, pins, splits)
        val moved = result.changes.mapTo(mutableSetOf()) { it.ref }
        val wanted = links + result.exclusions.filterNot { it.holdsApart(apart) }
        val (writable, held) = wanted.partition { link -> (link.matched + link.excluded).none { it in moved } }
        return MatchPlan(result, scope, planLinks(writable, held, scope))
    }

    private data class MatchPlan(
        val result: MatchResult,
        val scope: List<MatchObservation>,
        val links: LinkChanges,
    ) {
        val hasChanges: Boolean get() = result.changes.isNotEmpty() || links.writes.isNotEmpty()
    }

    private data class LinkChanges(
        val writes: List<RelationshipWrite> = emptyList(),
        val affected: Set<TransactionId> = emptySet(),
    )

    /** One stored exclusion link, optionally excluding the superseded legs it points to as well. */
    private data class PlannedLink(
        val primary: TransactionId,
        val related: Set<TransactionId>,
        val excludesRelated: Boolean = false,
    )

    /**
     * Makes the Reconciler's `reconciled` relationships among the scope's transactions what the match says, and returns
     * the ends of every one it wrote or deleted, to rebuild. The materializer reads exclusions from these links.
     *
     * Every other Reconciler link whose ends are all in scope goes: its records changed, an unimport took one away, or a
     * person split two of its observations, so it explains no exclusion any more. What [held] links cover (their records
     * move this run, so they are written on the next) stays as it is, and so does a link touching a frozen record.
     */
    private suspend fun planLinks(
        writable: List<ExclusionLink>,
        held: List<ExclusionLink>,
        scope: List<MatchObservation>,
    ): LinkChanges {
        val repository = relationships ?: return LinkChanges()
        val transactionOf = scope.mapNotNull { o -> o.current?.let { o.ref to it } }.toMap()
        val inScope = transactionOf.values.mapTo(mutableSetOf()) { it.id }
        if (inScope.isEmpty()) return LinkChanges()
        val planned = writable.flatMapTo(mutableSetOf()) { plan(it, transactionOf) }
        val heldIds = held.map { link -> (link.matched + link.excluded).mapNotNullTo(mutableSetOf()) { transactionOf[it]?.id } }
        val stored =
            repository
                .getMadeByTransactions(transactionOf.values.distinctBy { it.id }, SourceType.RECONCILER)
                .filter { it.relationshipType.id.id == WellKnownIds.RECONCILED_RELATIONSHIP_TYPE_ID }
        val kept = mutableSetOf<PlannedLink>()
        val deletes =
            stored.filter { relationship ->
                val ends = relationship.members.mapTo(mutableSetOf()) { it.transactionId.id }
                val asPlanned =
                    relationship.primary?.let { primary ->
                        PlannedLink(
                            primary,
                            relationship.members.filter { it.role == RelationshipRole.RELATED }.mapTo(mutableSetOf()) { it.transactionId },
                            relationship.excludesRelated,
                        )
                    }
                val keep =
                    asPlanned in planned ||
                        ends.any { it !in inScope } ||
                        heldIds.any { it.containsAll(ends) }
                if (keep && asPlanned != null) kept += asPlanned
                !keep
            }
        val creates = planned - kept
        return LinkChanges(
            deletes.map { RelationshipWrite.Delete(it.id) } +
                creates.map { link ->
                    RelationshipWrite.Create(
                        RelationshipTypeId(WellKnownIds.RECONCILED_RELATIONSHIP_TYPE_ID),
                        listOf(RelationshipMember(link.primary, RelationshipRole.PRIMARY)) +
                            link.related.sortedBy { it.id }.map { RelationshipMember(it, RelationshipRole.RELATED) },
                        Source.Reconciler,
                        excludesRelated = link.excludesRelated,
                    )
                },
            deletes.flatMapTo(mutableSetOf()) { link -> link.members.map { it.transactionId } } +
                creates.flatMap { it.related + it.primary },
        )
    }

    /** The stored links [link] makes: one per transaction it excludes, none when a record it names has none yet. */
    private fun plan(
        link: ExclusionLink,
        transactionOf: Map<ObservationRef, TransactionId>,
    ): List<PlannedLink> {
        val excluded = link.excluded.map { transactionOf[it] ?: return emptyList() }.distinctBy { it.id }
        val excludedIds = excluded.mapTo(mutableSetOf()) { it.id }
        val countedRefs = link.matched - link.excluded
        if (countedRefs.any { it !in transactionOf }) return emptyList()
        val countedOf = { refs: Collection<ObservationRef> ->
            refs.map { transactionOf.getValue(it) }.distinctBy { it.id }.filter { it.id !in excludedIds }
        }
        val counted = countedOf(countedRefs)
        if (counted.isEmpty()) return emptyList()
        val related = countedOf(countedRefs.filter { it.slot != ObservationSlot.FEE }).ifEmpty { counted }.toSet()
        if (link.reason == ShapeReason.CONDUIT_STATEMENT) {
            val statement = (link.matched intersect link.excluded).singleOrNull()?.let(transactionOf::get)
            if (statement != null) {
                // Keep the two links visible from the statement: the funding it matches and each onward leg it replaces.
                val onward = (link.excluded - link.matched).filter { it.slot.startsWith(ObservationSlot.CONDUIT_PREFIX) }
                val superseded = onward.mapTo(mutableSetOf(), transactionOf::getValue) - statement
                return (excluded - superseded).map { PlannedLink(it, related) } +
                    superseded.map { PlannedLink(statement, setOf(it), excludesRelated = true) }
            }
        }
        return excluded.map { PlannedLink(it, related) }
    }

    /** Relationships of each released transaction other than its link to the one it joins, moved onto that one. */
    private suspend fun moveRelationships(moves: Map<TransactionId, TransactionId>) {
        val repository = relationships ?: return
        if (moves.isEmpty()) return
        val byId = moves.mapKeys { it.key.id }
        val writes = mutableListOf<RelationshipWrite>()
        for (relationship in repository.getByTransactions(moves.keys)) {
            val members =
                relationship.members
                    .map { member -> byId[member.transactionId.id]?.let { member.copy(transactionId = it) } ?: member }
                    .distinctBy { it.transactionId.id }
            writes += RelationshipWrite.Delete(relationship.id)
            if (members.size >= 2) {
                writes +=
                    RelationshipWrite.Create(
                        relationship.relationshipType.id,
                        members,
                        Source.Reconciler,
                        excludesRelated = relationship.excludesRelated,
                    )
            }
        }
        if (writes.isNotEmpty()) repository.apply(writes)
    }

    /** Mark a staged row duplicate when its main transfer was folded into another transaction. */
    private suspend fun relinkRows(
        changes: List<AssignmentChange>,
        resolved: Map<TransactionId, TransactionId>,
    ) {
        val relinked =
            changes.mapNotNull { change ->
                val owner = change.ref.owner as? ObservationOwner.Record
                owner?.recordId?.takeIf {
                    change.ref.slot == ObservationSlot.MAIN && change.from?.let { resolved[it] } is TransferId
                }
            }
        if (relinked.isEmpty()) return
        val links = mutableMapOf<CsvImportId, MutableSet<Long>>()
        for (record in records.getRecords(relinked)) {
            val key = record.key as? ImportRecordKey.CsvRow
            if (key != null) links.getOrPut(key.csvImportId) { mutableSetOf() } += key.rowIndex
        }
        links.forEach { (csvImportId, rows) -> csvImports.updateRowStatusesBatch(csvImportId, ImportStatus.DUPLICATE.name, rows) }
    }

    private fun TransactionTarget.asTransaction(): ObservationTransaction =
        when (this) {
            is TransactionTarget.Existing -> ObservationTransaction.Existing(id)
            is TransactionTarget.New -> ObservationTransaction.New(newKey(anchor.owner, anchor.slot))
        }

    private fun newIdOf(
        target: TransactionTarget?,
        report: MaterializationReport,
    ): TransactionId? = (target as? TransactionTarget.New)?.let { report.newTransactionIds[newKey(it.anchor.owner, it.anchor.slot)] }

    private fun newKey(
        owner: ObservationOwner,
        slot: String,
    ) = LocalTransactionKey("match:$owner/$slot")
}

/**
 * The matcher stages whose exclusion links a sync writes. The conversion stages stay with the importers' own
 * reconcilers until their outcomes are reconciled with today's (#1426).
 */
private val WRITTEN_STAGE_REASONS = setOf(ShapeReason.TRADE_AGGREGATION)

/** Whether this link would hold together two observations a person split (#1430). */
private fun ExclusionLink.holdsApart(apart: List<Pair<ObservationRef, ObservationRef>>): Boolean {
    val members = matched + excluded
    return apart.any { (a, b) -> a in members && b in members }
}
