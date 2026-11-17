package com.moneymanager.importer

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.Relationship
import com.moneymanager.domain.model.RelationshipMember
import com.moneymanager.domain.model.RelationshipRole
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.calendar.BusinessDayCalendar
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.observation.ImportRecordKey
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
import com.moneymanager.observationmatcher.ObservationRef
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
        val loaded = loader.load(allAccounts.map { it.id }, families)
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
                .filter { link -> apart.none { (a, b) -> a in link.matched + link.excluded && b in link.matched + link.excluded } }
        orientLinks(links, scope)
        val involved = links.flatMapTo(mutableSetOf()) { it.matched + it.excluded }
        val twins = scope.filter { it.ref !in involved }
        val result =
            MatchScopeLoader
                .matcherFor(
                    families,
                    MatchScopeLoader.containerAccounts(scope),
                    calendarFor,
                ).match(twins, pins, splits)
        val separated = dropSplitLinks(apart, scope, involved)
        if (result.changes.isEmpty()) {
            return if (separated.isEmpty()) {
                MaterializationReport()
            } else {
                materialize(
                    emptyList(),
                    MaterializationRequest(Source.Reconciler, separated),
                )
            }
        }

        val contentOf = scope.associate { it.ref to it.content }
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
        val touched =
            result.changes
                .flatMap { listOfNotNull(it.from, (it.to as? TransactionTarget.Existing)?.id) }
                .mapTo(mutableSetOf()) { known[it] ?: it }
        val inScope = scope.mapNotNullTo(mutableSetOf()) { it.current?.id }
        val shaped = scope.filter { it.ref in involved }.mapNotNullTo(mutableSetOf()) { it.current?.id }
        val unlinked = dropStaleLinks(touched, inScope - shaped) + separated

        val report = materialize(mutations, MaterializationRequest(Source.Reconciler, unlinked))
        val resolved = known + destinations.mapNotNull { (from, to) -> newIdOf(to, report)?.let { from to it } }
        relinkRows(result.changes, resolved)
        return report
    }

    /**
     * Points every `reconciled` relationship within a shape match away from the record the match excludes in its
     * place (the placeholder, the gross leg), whichever import wrote it: the deduper links an incoming row to the
     * record it found, so the other import order wrote the same pair the other way round.
     */
    private suspend fun orientLinks(
        links: List<ExclusionLink>,
        scope: List<MatchObservation>,
    ) {
        val repository = relationships ?: return
        if (links.isEmpty()) return
        val transactionOf = scope.mapNotNull { o -> o.current?.let { o.ref to it } }.toMap()
        val primaries = mutableMapOf<Long, TransactionId>()
        val members = mutableMapOf<Long, Set<Long>>()
        for (link in links) {
            val ids = (link.matched + link.excluded).mapNotNull { transactionOf[it] }
            val primary =
                (link.matched intersect link.excluded)
                    .sortedWith(compareBy({ it.slot != ObservationSlot.MAIN }, { it.toString() }))
                    .firstNotNullOfOrNull { transactionOf[it] } ?: continue
            val idSet = ids.mapTo(mutableSetOf()) { it.id }
            for (id in idSet) {
                primaries[id] = primary
                members[id] = idSet
            }
        }
        val writes =
            repository
                .getByTransactions(primaries.values.distinctBy { it.id })
                .flatMap { reorient(it, primaries, members) }
        if (writes.isNotEmpty()) repository.apply(writes)
    }

    /** [relationship] rewritten with its link's primary as its primary, or nothing when it is no such link or already is. */
    private fun reorient(
        relationship: Relationship,
        primaries: Map<Long, TransactionId>,
        members: Map<Long, Set<Long>>,
    ): List<RelationshipWrite> {
        val ends = relationship.members
        val primary = ends.firstOrNull()?.let { primaries[it.transactionId.id] }
        val current = ends.firstOrNull { it.role == RelationshipRole.PRIMARY }
        val applies =
            relationship.relationshipType.id.id == WellKnownIds.RECONCILED_RELATIONSHIP_TYPE_ID &&
                ends.size == 2 &&
                primary != null &&
                current != null &&
                ends.all { it.transactionId.id in members[primary.id].orEmpty() } &&
                current.transactionId.id != primary.id &&
                ends.any { it.transactionId.id == primary.id }
        if (!applies) return emptyList()
        val roles =
            ends.map {
                RelationshipMember(
                    it.transactionId,
                    if (it.transactionId.id ==
                        primary.id
                    ) {
                        RelationshipRole.PRIMARY
                    } else {
                        RelationshipRole.RELATED
                    },
                )
            }
        return listOf(
            RelationshipWrite.Delete(relationship.id),
            RelationshipWrite.Create(relationship.relationshipType.id, roles, Source.Reconciler),
        )
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
                    .map { member -> byId[member.transactionId.id]?.let { RelationshipMember(it, member.role) } ?: member }
                    .distinctBy { it.transactionId.id }
            writes += RelationshipWrite.Delete(relationship.id)
            if (members.size >= 2) writes += RelationshipWrite.Create(relationship.relationshipType.id, members, Source.Reconciler)
        }
        if (writes.isNotEmpty()) repository.apply(writes)
    }

    /**
     * Deletes each `reconciled` link between transactions the match keeps apart, and returns their ends to rebuild. The
     * deduper linked a record to the other side it found, and excluded one end for it; where the matcher pairs that
     * record elsewhere, nothing explains the exclusion any more, so the end it hid counts again. Only links whose every
     * end is a [free] transaction (in scope, and in no shape match, whose links stay) go.
     */
    private suspend fun dropStaleLinks(
        touched: Set<TransactionId>,
        free: Set<Long>,
    ): Set<TransactionId> {
        val repository = relationships ?: return emptySet()
        if (touched.isEmpty()) return emptySet()
        val stale =
            repository.getByTransactions(touched).filter { link ->
                val ends = link.members.map { it.transactionId.id }.distinct()
                link.relationshipType.id.id == WellKnownIds.RECONCILED_RELATIONSHIP_TYPE_ID && ends.size >= 2 && ends.all { it in free }
            }
        if (stale.isEmpty()) return emptySet()
        repository.apply(stale.map { RelationshipWrite.Delete(it.id) })
        return stale.flatMapTo(mutableSetOf()) { link -> link.members.map { it.transactionId } }
    }

    /**
     * Deletes each `reconciled` link between the transactions of two observations a person split, and returns their ends
     * to rebuild: the deduper reconciled one record against the other and excluded an end for it, which the person said
     * is wrong, so both count. A link a shape match still holds stays.
     */
    private suspend fun dropSplitLinks(
        apart: List<Pair<ObservationRef, ObservationRef>>,
        scope: List<MatchObservation>,
        involved: Set<ObservationRef>,
    ): Set<TransactionId> {
        val repository = relationships ?: return emptySet()
        val transactionOf = scope.mapNotNull { o -> o.current?.let { o.ref to it } }.toMap()
        val pairs =
            apart
                .filter { (a, b) -> a !in involved && b !in involved }
                .mapNotNull { (a, b) -> transactionOf[a]?.let { x -> transactionOf[b]?.let { y -> setOf(x.id, y.id) } } }
                .filter { it.size == 2 }
        if (pairs.isEmpty()) return emptySet()
        val ends = pairs.flatMapTo(mutableSetOf()) { pair -> transactionOf.values.filter { it.id in pair } }
        val stale =
            repository.getByTransactions(ends).filter { link ->
                val ids = link.members.mapTo(mutableSetOf()) { it.transactionId.id }
                link.relationshipType.id.id == WellKnownIds.RECONCILED_RELATIONSHIP_TYPE_ID && pairs.any { ids.containsAll(it) }
            }
        if (stale.isEmpty()) return emptySet()
        repository.apply(stale.map { RelationshipWrite.Delete(it.id) })
        return stale.flatMapTo(mutableSetOf()) { link -> link.members.map { it.transactionId } }
    }

    /** Each staged row that observed a released transfer, marked a duplicate of the transfer that took its place. */
    private suspend fun relinkRows(
        changes: List<AssignmentChange>,
        resolved: Map<TransactionId, TransactionId>,
    ) {
        val relinked =
            changes.mapNotNull { change ->
                val to = change.from?.let { resolved[it] } as? TransferId
                val owner = change.ref.owner as? ObservationOwner.Record
                if (to != null && owner != null) owner.recordId to to else null
            }
        if (relinked.isEmpty()) return
        val keys = records.getRecords(relinked.map { it.first }).associate { it.id to it.key }
        val links = mutableMapOf<CsvImportId, MutableMap<Long, TransferId?>>()
        for ((recordId, to) in relinked) {
            val key = keys[recordId] as? ImportRecordKey.CsvRow
            if (key != null) links.getOrPut(key.csvImportId) { mutableMapOf() }[key.rowIndex] = to
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
