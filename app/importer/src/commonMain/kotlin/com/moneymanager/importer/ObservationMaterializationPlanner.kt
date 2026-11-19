package com.moneymanager.importer

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.JsonPath
import com.moneymanager.domain.model.RelationshipRole
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.SourceType
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.TransactionKind
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.observation.ImportRecordKey
import com.moneymanager.domain.model.observation.MaterializedTransaction
import com.moneymanager.domain.model.observation.MaterializedWrite
import com.moneymanager.domain.model.observation.Observation
import com.moneymanager.domain.model.observation.ObservationAttribute
import com.moneymanager.domain.model.observation.ObservationBooking
import com.moneymanager.domain.model.observation.ObservationChange
import com.moneymanager.domain.model.observation.ObservationContent
import com.moneymanager.domain.model.observation.ObservationOwner
import com.moneymanager.domain.model.observation.ObservedValues
import com.moneymanager.domain.repository.ImportRecordReadRepository
import com.moneymanager.domain.repository.ObservationReadRepository
import com.moneymanager.domain.repository.PersonOverrideReadRepository
import com.moneymanager.domain.repository.RelationshipReadRepository
import com.moneymanager.importengineapi.LocalTransactionKey
import com.moneymanager.importengineapi.MaterializationConflict
import com.moneymanager.importengineapi.MaterializationReport
import com.moneymanager.importengineapi.MaterializationRequest
import com.moneymanager.importengineapi.Materializer
import com.moneymanager.importengineapi.ObservationMutation
import com.moneymanager.importengineapi.ObservationTransaction
import com.moneymanager.importengineapi.TransactionOverrides
import kotlin.time.Instant

internal data class ObservationMaterializationPlan(
    val newTransactions: Map<LocalTransactionKey, TransactionId>,
    val observations: List<ObservationChange>,
    val rows: List<MaterializedWrite>,
    val report: MaterializationReport,
    val candidateAccountIds: Set<AccountId>,
)

/** Computes observation and effective row changes over the touched slots and transactions. */
internal class ObservationMaterializationPlanner(
    private val observations: ObservationReadRepository,
    private val overrides: PersonOverrideReadRepository,
    private val relationships: RelationshipReadRepository?,
    private val placeholderTypeId: AttributeTypeId?,
    private val records: ImportRecordReadRepository?,
) {
    suspend fun plan(
        mutations: List<ObservationMutation>,
        request: MaterializationRequest,
        allocatedLocals: Int = 0,
    ): ObservationMaterializationPlan {
        val overlay = ObservationOverlay(observations, allocatedLocals)
        mutations.forEach { overlay.add(it) }
        val observationChanges = overlay.changes()
        val affected = request.transactionIds + overlay.affected
        val changes = observationChanges.groupBy { it.after?.transactionId }
        val stored =
            observations.getObservationsOfTransactions(affected.filterNot(overlay::isNew)).groupBy { it.transactionId }
        val replaced = observationChanges.mapNotNullTo(mutableSetOf()) { it.before?.id }
        val merged =
            affected.associateWith { id ->
                val remaining = stored[id].orEmpty().filterNot { it.id in replaced }.map { it.content }
                val incoming = changes[id].orEmpty().map { checkNotNull(it.after).content }
                Materializer.merge(remaining + incoming, placeholderTypeId)
            }
        val (gone, live) = merged.keys.partition { merged[it] == null }
        val excludedByLink = excludedByLinks(live.filterNot(overlay::isNew), gone.mapTo(mutableSetOf()) { it.id })
        val writes = mutableListOf<MaterializedWrite>()
        val conflicts = mutableListOf<MaterializationConflict>()
        for (id in affected) {
            val isNew = overlay.isNew(id)
            val current = if (isNew) null else observations.getMaterialized(id)
            val merge = merged[id]?.let { if (id.id in excludedByLink) it.excludedAsReconciled() else it }
            val personal = if (isNew) TransactionOverrides() else overridesOf(id)
            val effective = merge?.let { Materializer.applyOverrides(it, personal) }
            val source =
                if (current == null && effective != null && request.source == Source.Reconciler) {
                    val owners =
                        stored[id].orEmpty().filterNot { it.id in replaced }.map { it.owner } + changes[id].orEmpty().map { it.owner }
                    creatorOf(owners) ?: request.source
                } else {
                    request.source
                }
            Materializer.write(id.id, id.kind(), current, effective, source)?.let(writes::add)
            if (!personal.isEmpty) {
                val before = request.priorMerges[id] ?: Materializer.merge(stored[id].orEmpty().map { it.content }, placeholderTypeId)
                conflicts += Materializer.conflicts(id.id, before, merge, personal)
            }
        }
        return ObservationMaterializationPlan(
            newTransactions = overlay.newTransactions,
            observations = observationChanges,
            rows = writes,
            report = report(writes, conflicts, affected.associateBy { it.id }, observationChanges),
            candidateAccountIds = request.candidateAccountIds,
        )
    }

    private suspend fun report(
        writes: List<MaterializedWrite>,
        conflicts: List<MaterializationConflict>,
        affected: Map<Long, TransactionId>,
        changes: List<ObservationChange>,
    ): MaterializationReport {
        val deleted = writes.idsOf<MaterializedWrite.Delete>(affected)
        // Deleting or replacing an observation also deletes its pin, before the effective rows are applied.
        val removedObservations =
            changes.mapNotNullTo(mutableSetOf()) { change ->
                change.before?.id?.takeIf { it != change.after?.storedId }
            }
        val pins =
            if (deleted.isEmpty()) {
                emptyList()
            } else {
                overrides.getPinsOnTransactions(deleted).filterNot { it.observationId in removedObservations }
            }
        val lostMembers = if (deleted.isEmpty()) emptyList() else relationships?.getByTransactions(deleted).orEmpty()
        val (matches, others) =
            lostMembers.partition { it.relationshipType.id.id == WellKnownIds.RECONCILIATION_MATCH_RELATIONSHIP_TYPE_ID }
        return MaterializationReport(
            inserted = writes.idsOf<MaterializedWrite.Insert>(affected),
            updated = writes.idsOf<MaterializedWrite.Update>(affected),
            deleted = deleted,
            conflicts = conflicts,
            orphanedPins = pins,
            orphanedMatches = matches,
            orphanedRelationships = others,
        )
    }

    /** A surviving Reconciler link determines exclusions even though no observation says the movement is excluded. */
    private suspend fun excludedByLinks(
        live: Collection<TransactionId>,
        gone: Set<Long>,
    ): Set<Long> {
        if (live.isEmpty()) return emptySet()
        val liveIds = live.mapTo(mutableSetOf()) { it.id }
        return relationships
            ?.getMadeByTransactions(live, SourceType.RECONCILER)
            .orEmpty()
            .filter { link ->
                link.relationshipType.id.id == WellKnownIds.RECONCILED_RELATIONSHIP_TYPE_ID &&
                    link.members.any { it.role == RelationshipRole.RELATED && it.transactionId.id !in gone }
            }.flatMapTo(mutableSetOf()) { link ->
                val primary = link.primary ?: return@flatMapTo emptyList()
                if (primary.id in gone) return@flatMapTo emptyList()
                val excluded = if (link.excludesRelated) link.members.map { it.transactionId } else listOf(primary)
                excluded.mapNotNull { it.id.takeIf(liveIds::contains) }
            }
    }

    /** A transaction split off by the matcher belongs to its record if no other record or group reports it. */
    private suspend fun creatorOf(owners: List<ObservationOwner>): Source? {
        val owner = owners.distinct().singleOrNull() as? ObservationOwner.Record ?: return null
        return when (val key = records?.getRecord(owner.recordId)?.key) {
            is ImportRecordKey.CsvRow -> Source.Csv(key.csvImportId, key.rowIndex)
            is ImportRecordKey.ApiItem -> Source.Api(key.sessionId, key.requestId, JsonPath(key.jsonPath))
            null -> null
        }
    }

    private suspend fun overridesOf(id: TransactionId): TransactionOverrides =
        when (id) {
            is TransferId -> TransactionOverrides(overrides.getTransferOverrides(id), overrides.getTransferAttributeOverrides(id))
            is TradeId -> TransactionOverrides(attributes = overrides.getTradeAttributeOverrides(id))
            else -> error("transaction $id is neither a transfer nor a trade")
        }

    private inline fun <reified W : MaterializedWrite> List<MaterializedWrite>.idsOf(
        affected: Map<Long, TransactionId>,
    ): Set<TransactionId> = filterIsInstance<W>().mapTo(mutableSetOf()) { affected.getValue(it.transactionId) }
}

/** The slot changes in this plan. Unchanged observations still come from the read repository. */
private class ObservationOverlay(
    private val observations: ObservationReadRepository,
    private val allocatedLocals: Int,
) {
    private val storedByOwner = mutableMapOf<ObservationOwner, Map<String, Observation>>()
    private val bySlot = linkedMapOf<Pair<ObservationOwner, String>, ObservationChange>()
    val newTransactions = linkedMapOf<LocalTransactionKey, TransactionId>()
    val affected = mutableSetOf<TransactionId>()

    fun changes(): List<ObservationChange> = bySlot.values.filter { it.before?.planned() != it.after }

    fun isNew(id: TransactionId): Boolean = id.id < 0

    suspend fun add(mutation: ObservationMutation) {
        val key = mutation.owner to mutation.slot
        val current =
            bySlot.getOrPut(key) {
                val stored =
                    storedByOwner.getOrPut(mutation.owner) {
                        observations.getObservationsOf(mutation.owner).associateBy { it.slot }
                    }[mutation.slot]
                ObservationChange(mutation.owner, mutation.slot, stored, stored?.planned())
            }
        current.after?.transactionId?.let(affected::add)
        val after =
            when (mutation) {
                is ObservationMutation.Delete -> {
                    null
                }

                is ObservationMutation.Upsert -> {
                    val target = resolve(mutation)
                    require(
                        current.after
                            ?.content
                            ?.values
                            ?.kind
                            ?.let { it == target.kind() } != false,
                    ) {
                        "observation ${mutation.owner}/${mutation.slot} cannot change kind"
                    }
                    affected += target
                    ObservationBooking(current.after?.storedId, target, mutation.content.atStoredPrecision())
                }
            }
        bySlot[key] = current.copy(after = after)
    }

    private fun resolve(mutation: ObservationMutation.Upsert): TransactionId {
        val kind = mutation.content.values.kind
        val id =
            when (val target = mutation.transaction) {
                is ObservationTransaction.Existing -> {
                    target.id.also { require(!isNew(it)) }
                }

                is ObservationTransaction.New -> {
                    newTransactions.getOrPut(target.key) {
                        val provisional = -(allocatedLocals.toLong() + newTransactions.size + 1)
                        when (kind) {
                            TransactionKind.TRANSFER -> TransferId(provisional)
                            TransactionKind.TRADE -> TradeId(provisional)
                        }
                    }
                }
            }
        require(id.kind() == kind) { "a $kind observation can't be on transaction $id" }
        return id
    }
}

private fun Observation.planned() = ObservationBooking(id, transactionId, content)

/** Observation timestamps persist at millisecond precision. Merge the same values a reader will see after apply. */
private fun ObservationContent.atStoredPrecision(): ObservationContent =
    copy(
        values =
            when (val value = values) {
                is ObservedValues.Transfer -> value.copy(timestamp = Instant.fromEpochMilliseconds(value.timestamp.toEpochMilliseconds()))
                is ObservedValues.Trade -> value.copy(timestamp = Instant.fromEpochMilliseconds(value.timestamp.toEpochMilliseconds()))
            },
    )

private fun TransactionId.kind(): TransactionKind =
    when (this) {
        is TransferId -> TransactionKind.TRANSFER
        is TradeId -> TransactionKind.TRADE
        else -> error("transaction $this is neither a transfer nor a trade")
    }

private val EXCLUDED_TYPE = AttributeTypeId(WellKnownIds.EXCLUDED_ATTR_TYPE_ID)

private fun MaterializedTransaction.excludedAsReconciled(): MaterializedTransaction =
    if (attributes.any { it.typeId == EXCLUDED_TYPE }) {
        this
    } else {
        copy(attributes = attributes + ObservationAttribute(EXCLUDED_TYPE, "", "reconciled"))
    }
