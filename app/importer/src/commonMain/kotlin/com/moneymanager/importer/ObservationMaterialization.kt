package com.moneymanager.importer

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.TransactionKind
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.observation.MaterializedTransaction
import com.moneymanager.domain.model.observation.MaterializedWrite
import com.moneymanager.domain.model.observation.Observation
import com.moneymanager.domain.model.observation.ObservationAttribute
import com.moneymanager.domain.model.observation.ObservationOwner
import com.moneymanager.domain.repository.PersonOverrideReadRepository
import com.moneymanager.domain.repository.RelationshipReadRepository
import com.moneymanager.domain.repository.TradeReadRepository
import com.moneymanager.domain.repository.write.AccountWriteRepository
import com.moneymanager.domain.repository.write.ObservationWriteRepository
import com.moneymanager.importengineapi.LocalTransactionKey
import com.moneymanager.importengineapi.MaterializationConflict
import com.moneymanager.importengineapi.MaterializationReport
import com.moneymanager.importengineapi.MaterializationRequest
import com.moneymanager.importengineapi.Materializer
import com.moneymanager.importengineapi.ObservationMutation
import com.moneymanager.importengineapi.ObservationTransaction
import com.moneymanager.importengineapi.TransactionOverrides

private val EXCLUDED_TYPE = AttributeTypeId(WellKnownIds.EXCLUDED_ATTR_TYPE_ID)

/** The exclusion reason a reconciled link gives its related end, as the deduper writes it. */
private const val RECONCILED_EXCLUSION = "reconciled"

/**
 * The materializer's database half, run by [ImportEngineImpl]: applies a batch's observation mutations, then brings
 * every transaction they touched to the rank-merge of its observations with the person's overrides on top, writing
 * only what changed ([Materializer] decides what). A transaction left without observations loses its row, and what
 * pointed at it is reported rather than guessed at. Accounts the request names that nothing references any more
 * go too, each with its fee account.
 */
internal class ObservationMaterialization(
    private val observations: ObservationWriteRepository,
    private val overrides: PersonOverrideReadRepository,
    private val accounts: AccountWriteRepository,
    private val trades: TradeReadRepository,
    private val relationships: RelationshipReadRepository?,
    private val placeholderTypeId: AttributeTypeId?,
) {
    suspend fun run(
        mutations: List<ObservationMutation>,
        request: MaterializationRequest,
    ): MaterializationReport {
        val bySlot = mutableMapOf<ObservationOwner, MutableMap<String, Observation>>()

        suspend fun slotsOf(owner: ObservationOwner) =
            bySlot.getOrPut(owner) { observations.getObservationsOf(owner).associateByTo(mutableMapOf()) { it.slot } }

        // Every transaction an observation leaves or joins, keyed by its id (a transfer's and a trade's never clash).
        val affected = LinkedHashMap<Long, TransactionId>()
        (request.transactionIds + request.linkExcluded + request.linkCounted).forEach { affected[it.id] = it }
        for (mutation in mutations) {
            slotsOf(mutation.owner)[mutation.slot]?.let { affected[it.transactionId.id] = it.transactionId }
            ((mutation as? ObservationMutation.Upsert)?.transaction as? ObservationTransaction.Existing)?.let {
                affected[it.id.id] = it.id
            }
        }
        val before = affected.mapValues { (_, id) -> request.priorMerges[id] ?: mergeOf(id) }

        val newIds = LinkedHashMap<LocalTransactionKey, TransactionId>()
        for (mutation in mutations) {
            val slots = slotsOf(mutation.owner)
            val existing = slots[mutation.slot]
            when (mutation) {
                is ObservationMutation.Upsert -> {
                    val target =
                        when (val transaction = mutation.transaction) {
                            is ObservationTransaction.Existing -> {
                                transaction.id
                            }

                            is ObservationTransaction.New -> {
                                newIds.getOrPut(transaction.key) {
                                    observations.allocateTransactionId(mutation.content.values.kind)
                                }
                            }
                        }
                    affected[target.id] = target
                    slots[mutation.slot] =
                        if (existing == null) {
                            val id = observations.insertObservation(mutation.owner, mutation.slot, target, mutation.content)
                            Observation(id, mutation.owner, mutation.slot, target, mutation.content)
                        } else {
                            if (existing.content != mutation.content) observations.updateContent(existing.id, mutation.content)
                            if (existing.transactionId.id != target.id) observations.assignTransaction(existing.id, target)
                            existing.copy(transactionId = target, content = mutation.content)
                        }
                }

                is ObservationMutation.Delete -> {
                    if (existing != null) {
                        observations.deleteObservation(existing.id)
                        slots.remove(mutation.slot)
                    }
                }
            }
        }

        val mergedByKey = affected.mapValues { (_, id) -> mergeOf(id) }
        val gone = mergedByKey.filterValues { it == null }.keys
        val linked = reconciledLinked(affected.filterKeys { it !in gone }.values, gone)
        val excludedByLink = request.linkExcluded.mapTo(mutableSetOf()) { it.id }
        val countedByLink = request.linkCounted.mapTo(mutableSetOf()) { it.id }
        val writes = mutableListOf<MaterializedWrite>()
        val conflicts = mutableListOf<MaterializationConflict>()
        for ((key, id) in affected) {
            val current = observations.getMaterialized(id)
            val merged =
                mergedByKey[key]?.let {
                    when (key) {
                        in excludedByLink -> it.excludedAsReconciled()
                        in countedByLink -> it
                        in linked if current.isReconciledExclusion() -> it.excludedAsReconciled()
                        else -> it
                    }
                }
            val personal = overridesOf(id)
            val effective = merged?.let { Materializer.applyOverrides(it, personal) }
            Materializer.write(key, id.kind(), current, effective, request.source)?.let(writes::add)
            conflicts += Materializer.conflicts(key, before[key], merged, personal)
        }

        val deleted = writes.filterIsInstance<MaterializedWrite.Delete>().map { affected.getValue(it.transactionId) }
        // Read before the rows go, while every member still resolves.
        val pins = if (deleted.isEmpty()) emptyList() else overrides.getPinsOnTransactions(deleted)
        val lostMembers = if (deleted.isEmpty()) emptyList() else relationships?.getByTransactions(deleted).orEmpty()
        val (matches, others) =
            lostMembers.partition { it.relationshipType.id.id == WellKnownIds.RECONCILIATION_MATCH_RELATIONSHIP_TYPE_ID }

        if (writes.isNotEmpty()) observations.applyMaterialization(writes)

        return MaterializationReport(
            newTransactionIds = newIds,
            inserted = writes.idsOf<MaterializedWrite.Insert>(affected),
            updated = writes.idsOf<MaterializedWrite.Update>(affected),
            deleted = deleted.toSet(),
            conflicts = conflicts,
            orphanedPins = pins,
            orphanedMatches = matches,
            orphanedRelationships = others,
            deletedAccountIds = collectAccounts(request.candidateAccountIds),
        )
    }

    /**
     * The ids of [live] transactions in a `reconciled` relationship with another transaction that outlives this run
     * ([gone] are those it deletes). Today's dedupe excludes one end of such a link, and no observation says so (each
     * record reports only itself), so a linked row keeps the exclusion it has until the link or its other end goes.
     * The link's roles don't say which end that is, so only the row does.
     */
    private suspend fun reconciledLinked(
        live: Collection<TransactionId>,
        gone: Set<Long>,
    ): Set<Long> {
        if (live.isEmpty()) return emptySet()
        val liveIds = live.mapTo(mutableSetOf()) { it.id }
        return relationships
            ?.getByTransactions(live)
            .orEmpty()
            .filter { it.relationshipType.id.id == WellKnownIds.RECONCILED_RELATIONSHIP_TYPE_ID }
            .flatMap { link ->
                val ends = link.members.map { it.transactionId.id }.filter { it !in gone }
                if (ends.size < 2) emptyList() else ends
            }.filterTo(mutableSetOf()) { it in liveIds }
    }

    private fun MaterializedTransaction?.isReconciledExclusion(): Boolean =
        this != null && attributes.any { it.typeId == EXCLUDED_TYPE && it.value == RECONCILED_EXCLUSION }

    private fun MaterializedTransaction.excludedAsReconciled(): MaterializedTransaction =
        if (attributes.any { it.typeId == EXCLUDED_TYPE }) {
            this
        } else {
            copy(
                attributes =
                    attributes + ObservationAttribute(EXCLUDED_TYPE, "", RECONCILED_EXCLUSION),
            )
        }

    private suspend fun mergeOf(id: TransactionId): MaterializedTransaction? =
        Materializer.merge(observations.getObservationsOfTransaction(id).map { it.content }, placeholderTypeId)

    private suspend fun overridesOf(id: TransactionId): TransactionOverrides =
        when (id) {
            is TransferId -> TransactionOverrides(overrides.getTransferOverrides(id), overrides.getTransferAttributeOverrides(id))
            is TradeId -> TransactionOverrides(attributes = overrides.getTradeAttributeOverrides(id))
            else -> error("transaction $id is neither a transfer nor a trade")
        }

    /**
     * Deletes the [candidates] nothing references (no transfer, trade or observation), then the fee accounts of those
     * that nothing references either: a fee account belongs to its account and goes with it.
     */
    private suspend fun collectAccounts(candidates: Set<AccountId>): Set<AccountId> {
        if (candidates.isEmpty()) return emptySet()
        val unreferenced = unreferenced(candidates)
        if (unreferenced.isEmpty()) return emptySet()
        val feeAccounts =
            unreferenced
                .flatMap { accounts.getAccountIdsByAttribute(WellKnownIds.ACCOUNT_FEE_ACCOUNT_FOR_ATTR_TYPE_ID, it.id.toString()) }
                .toSet() - unreferenced
        val garbage = unreferenced + unreferenced(feeAccounts)
        accounts.deleteAccounts(garbage)
        return garbage
    }

    private suspend fun unreferenced(accountIds: Set<AccountId>): Set<AccountId> {
        if (accountIds.isEmpty()) return emptySet()
        return accountIds -
            accounts.accountsWithTransfers(accountIds) -
            trades.accountsWithTrades(accountIds) -
            observations.getAccountsWithObservations(accountIds)
    }

    private inline fun <reified W : MaterializedWrite> List<MaterializedWrite>.idsOf(
        affected: Map<Long, TransactionId>,
    ): Set<TransactionId> = filterIsInstance<W>().mapTo(mutableSetOf()) { affected.getValue(it.transactionId) }

    private fun TransactionId.kind(): TransactionKind =
        when (this) {
            is TransferId -> TransactionKind.TRANSFER
            is TradeId -> TransactionKind.TRADE
            else -> error("transaction $this is neither a transfer nor a trade")
        }
}
