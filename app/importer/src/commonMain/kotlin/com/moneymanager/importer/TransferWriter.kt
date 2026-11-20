package com.moneymanager.importer

import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.NewRelationship
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TransactionKind
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.repository.write.RelationshipTypeWriteRepository
import com.moneymanager.domain.repository.write.RelationshipWriteRepository
import com.moneymanager.domain.repository.write.TransactionWriteRepository
import com.moneymanager.domain.repository.write.TransferUpdate
import com.moneymanager.importengineapi.ExpandedRow
import com.moneymanager.importengineapi.ExpandedTransfer
import com.moneymanager.importengineapi.ExpansionRow
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.LegLink
import com.moneymanager.importengineapi.LegLinkTarget
import com.moneymanager.importengineapi.ObservationExpander
import com.moneymanager.importengineapi.ObservationKey
import com.moneymanager.importengineapi.ObservationSlot
import com.moneymanager.importengineapi.forRow
import com.moneymanager.importengineapi.observationOwner
import com.moneymanager.tasks.Progress
import kotlinx.coroutines.flow.update

/** Writes a batch's new and updated transfers, with their derived legs, relationships and exclusions. */
internal class TransferWriter(
    private val transactionRepository: TransactionWriteRepository,
    private val relationshipTypeRepository: RelationshipTypeWriteRepository,
    private val relationshipRepository: RelationshipWriteRepository,
) {
    /**
     * Creates the IMPORTED transfers and applies the UPDATED ones. [batchSize] transfers are written per
     * database transaction: the default ([Int.MAX_VALUE]) writes everything (creates + updates) in one
     * transaction — the historical behaviour — while a smaller value chunks the creates across several
     * transactions and reports per-chunk progress, so a huge batch (e.g. sample-data generation) doesn't
     * freeze on one giant transaction. Updates ride with the final create chunk, so the single-chunk case
     * still applies creates and updates atomically together. Returns the created ids, aligned to
     * [toImport] order.
     */
    suspend fun writeTransfers(
        toImport: List<Classified>,
        toUpdate: List<Classified>,
        batchSize: Int,
        onProgress: (suspend (Progress) -> Unit)?,
        engineOwnedTypeId: AttributeTypeId?,
        createdLegs: MutableList<Pair<ExpandedTransfer, TransferId>>,
        observer: SourceObservations,
    ): List<TransferId> {
        if (toImport.isEmpty() && toUpdate.isEmpty()) return emptyList()

        val effectiveBatchSize = batchSize.coerceAtLeast(1)
        if (toImport.isEmpty()) {
            // No creates, only updates: apply them in their own transaction.
            val (updates, updateSources) = buildUpdates(toUpdate, engineOwnedTypeId)
            transactionRepository.importTransfers(
                transfers = emptyList(),
                newAttributes = emptyMap(),
                newRelationships = emptyMap(),
                sources = emptyList(),
                updates = updates,
                updateSources = updateSources,
            )
            return emptyList()
        }

        // Resolve (get-or-create) every relationship type named by a batch relationship once, so the
        // expander (pure) can look ids up synchronously.
        val batchRelTypeIds: Map<String, RelationshipTypeId> =
            toImport
                .flatMap { it.transfer.batchRelationships }
                .map { it.typeName }
                .distinct()
                .associateWith { relationshipTypeRepository.getOrCreate(it) }
        val expanded = ObservationExpander.expandTransfers(toImport.toExpansionRows(), batchRelTypeIds)

        val total = toImport.size
        val createdIds = mutableListOf<TransferId>()
        // Real ids of the legs already-written chunks created, so a link whose target landed in an earlier
        // chunk (its temp id no longer resolvable) still links by real id.
        val priorIds = mutableMapOf<ObservationKey, TransferId>()
        var written = 0
        // Fast-path the common single-transaction case (default batchSize) so it doesn't copy the whole list.
        val chunks = if (effectiveBatchSize >= expanded.size) listOf(expanded) else expanded.chunked(effectiveBatchSize)
        chunks.forEachIndexed { index, chunk ->
            val payload = buildCreatePayload(chunk, priorIds)
            // A leg whose record a re-run reserved an id for goes back in under it (#1430).
            val reusedIds = mutableMapOf<TransferId, TransferId>()
            val joinedIds = mutableMapOf<TransferId, TransferId>()
            chunk.flatMap { it.legs }.forEachIndexed { i, leg ->
                val placeholder = payload.transfers[i].id
                val reused = observer.reuse(leg.key, TransactionKind.TRANSFER) as TransferId?
                if (reused != null) {
                    reusedIds[placeholder] = reused
                } else {
                    (observer.joined(leg.key, TransactionKind.TRANSFER) as TransferId?)?.let { joinedIds[placeholder] = it }
                }
            }
            // Updates ride with the final create chunk (one transaction in the common single-chunk case).
            val (updates, updateSources) =
                if (index == chunks.lastIndex) buildUpdates(toUpdate, engineOwnedTypeId) else emptyList<TransferUpdate>() to emptyList()
            val allCreatedIds =
                transactionRepository.importTransfers(
                    transfers = payload.transfers,
                    newAttributes = payload.newAttributes,
                    newRelationships = payload.newRelationships,
                    sources = payload.sources,
                    updates = updates,
                    updateSources = updateSources,
                    reusedIds = reusedIds,
                    joinedIds = joinedIds,
                )
            // Keep only the main transfers' ids (fee transfers are interleaved), aligned to [toImport].
            createdIds += payload.mainResultIndices.map { allCreatedIds[it] }
            chunk.flatMap { it.legs }.forEachIndexed { i, leg ->
                priorIds[leg.key] = allCreatedIds[i]
                createdLegs += leg to allCreatedIds[i]
            }
            written += chunk.size
            onProgress?.invoke(
                Progress(
                    detail = "Importing transactions",
                    fraction = written.toFloat() / total,
                    processed = written,
                    total = total,
                ),
            )
        }
        require(toImport.size == createdIds.size)
        return createdIds
    }

    /** What the expander needs of each row to import: the row and what classification decided about it. */
    private fun List<Classified>.toExpansionRows(): List<ExpansionRow> {
        val owners = map { it.transfer.observationOwner() }
        return map { classified ->
            ExpansionRow(
                transfer = classified.transfer,
                reversals =
                    classified.reversalLinks.mapValues { (_, link) ->
                        val target =
                            when (val reversed = link.target) {
                                is ReversalTarget.Existing -> {
                                    LegLinkTarget.Existing(reversed.id)
                                }

                                is ReversalTarget.BatchRow -> {
                                    LegLinkTarget.Expanded(
                                        ObservationKey(owners[reversed.toImportIndex], ObservationSlot.conduit(reversed.legIndex)),
                                    )
                                }
                            }
                        LegLink(target, link.typeId)
                    },
            )
        }
    }

    /** The transfers (with fees expanded), attributes, relationships and sources to create for one chunk. */
    private class CreatePayload(
        val transfers: List<Transfer>,
        val newAttributes: Map<TransferId, List<NewAttribute>>,
        val newRelationships: Map<TransferId, List<NewRelationship>>,
        val sources: List<Source>,
        // Indices into [transfers] of each main transfer, so callers map results back to chunk order.
        val mainResultIndices: List<Int>,
    )

    /**
     * Writes [chunk]'s expanded legs with negative temp ids, so newAttributes/newRelationships can be keyed before
     * real ids exist. A link resolves to a leg of this chunk by its temp id (the repository's in-batch id map is per
     * importTransfers call, so a main and its fee and spend legs stay in one chunk), else to a leg [priorIds] holds
     * from an earlier chunk; one to a leg of a later chunk is dropped.
     */
    private fun buildCreatePayload(
        chunk: List<ExpandedRow>,
        priorIds: Map<ObservationKey, TransferId>,
    ): CreatePayload {
        val legs = chunk.flatMap { it.legs }
        val tempIds = legs.indices.map { TransferId(-(it + 1).toLong()) }
        // Last wins, as the expander's own row-key lookup does.
        val tempIdByKey = legs.zip(tempIds).associate { (leg, id) -> leg.key to id }
        val newAttributes = mutableMapOf<TransferId, List<NewAttribute>>()
        val newRelationships = mutableMapOf<TransferId, List<NewRelationship>>()
        legs.forEachIndexed { i, leg ->
            if (leg.attributes.isNotEmpty()) newAttributes[tempIds[i]] = leg.attributes
            val relationships =
                leg.links.mapNotNull { link ->
                    val target =
                        when (val t = link.target) {
                            is LegLinkTarget.Existing -> t.id
                            is LegLinkTarget.Expanded -> tempIdByKey[t.key] ?: priorIds[t.key]
                        }
                    target?.let { NewRelationship(relatedTransferId = it, typeId = link.typeId) }
                }
            if (relationships.isNotEmpty()) newRelationships[tempIds[i]] = relationships
        }
        val transfers =
            legs.mapIndexed { i, leg ->
                Transfer(
                    id = tempIds[i],
                    timestamp = leg.timestamp,
                    description = leg.description,
                    sourceAccountId = leg.sourceAccountId,
                    targetAccountId = leg.targetAccountId,
                    amount = leg.amount,
                )
            }
        val mainResultIndices = chunk.runningFold(0) { start, row -> start + row.legs.size }.dropLast(1)
        return CreatePayload(transfers, newAttributes, newRelationships, legs.map { it.provenance }, mainResultIndices)
    }

    private suspend fun buildUpdates(
        toUpdate: List<Classified>,
        engineOwnedTypeId: AttributeTypeId?,
    ): Pair<List<TransferUpdate>, List<Source>> {
        val updates = mutableListOf<TransferUpdate>()
        val orderedUpdateSources = mutableListOf<Source>()
        val excludedTypeId = AttributeTypeId(WellKnownIds.EXCLUDED_ATTR_TYPE_ID)
        // Main transfers whose exclusion the source now asserts (true) or has withdrawn (false).
        val exclusionChanges = mutableMapOf<TransferId, ExclusionChange>()
        for (classified in toUpdate) {
            val existingId = classified.existing ?: continue
            val t = classified.transfer
            val reported = t.attributes.mapTo(mutableSetOf()) { it.typeId }
            val removed = t.ownedAttributes.filterKeys { it !in reported && it != engineOwnedTypeId }
            val source = t.source.forRow(requireNotNull(t.rowKey))
            updates +=
                TransferUpdate(
                    transfer =
                        Transfer(
                            id = existingId,
                            timestamp = requireNotNull(t.timestamp),
                            description = t.description,
                            sourceAccountId = requireId(t.fromAccount),
                            targetAccountId = requireId(t.toAccount),
                            amount = requireNotNull(t.amount),
                        ),
                    newAttributes = t.attributes,
                    removedAttributes = removed,
                )
            orderedUpdateSources += source
            if (t.ownedAttributes.containsKey(excludedTypeId)) {
                exclusionChanges[existingId] =
                    ExclusionChange(t.attributes.firstOrNull { it.typeId == excludedTypeId }, t.ownedAttributes[excludedTypeId], source)
            }
        }
        derivedLegExclusionUpdates(exclusionChanges, excludedTypeId, updates.mapTo(mutableSetOf()) { it.transfer.id })
            .forEach { (update, source) ->
                updates += update
                orderedUpdateSources += source
            }
        return updates to orderedUpdateSources
    }

    /**
     * The legs derived from a main transfer — its fee and its pass-through spend legs (C1→C2, …, Cn→merchant,
     * each linked to the next) — follow its exclusion when a re-import changes it, as they do on creation
     * (see buildCreatePayload): a row the source deleted mustn't move balances through them, and one it
     * un-deleted must again.
     */
    private suspend fun derivedLegExclusionUpdates(
        exclusionChanges: Map<TransferId, ExclusionChange>,
        excludedTypeId: AttributeTypeId,
        alreadyUpdated: Set<TransferId>,
    ): List<Pair<TransferUpdate, Source>> {
        val relationships = relationshipRepository
        if (exclusionChanges.isEmpty()) return emptyList()
        val derivedTypes = setOf(WellKnownIds.FEE_RELATIONSHIP_TYPE_ID, WellKnownIds.PASS_THROUGH_RELATIONSHIP_TYPE_ID)
        // Derived leg -> the main transfer it hangs off, walking fee/pass-through links outwards (primary -> related).
        val mainOf = mutableMapOf<TransferId, TransferId>()
        var frontier: Map<TransferId, TransferId> = exclusionChanges.keys.associateWith { it }
        while (frontier.isNotEmpty()) {
            val next =
                relationships
                    .getByTransactions(frontier.keys)
                    .filter { it.relationshipType.id.id in derivedTypes }
                    .mapNotNull { it.transferPair }
                    .filter { (main, leg) -> main in frontier && leg !in mainOf && leg !in exclusionChanges }
                    .associate { (main, leg) -> leg to frontier.getValue(main) }
            mainOf += next
            frontier = next
        }
        mainOf.keys.removeAll(alreadyUpdated)
        if (mainOf.isEmpty()) return emptyList()
        return transactionRepository.getTransactionsByIds(mainOf.keys).mapNotNull { (legId, leg) ->
            val change = exclusionChanges.getValue(mainOf.getValue(legId))
            val stored = leg.attributes.firstOrNull { it.attributeType.id == excludedTypeId }
            when {
                change.exclusion != null && stored == null -> {
                    TransferUpdate(leg, listOf(change.exclusion)) to change.source
                }

                // Only lift an exclusion the source itself set (see ImportTransfer.ownedAttributes).
                change.exclusion == null && stored != null && change.ownedValues.let { it == null || stored.value in it } -> {
                    TransferUpdate(leg, emptyList(), mapOf(excludedTypeId to change.ownedValues)) to change.source
                }

                else -> {
                    null
                }
            }
        }
    }

    /** A re-imported main transfer's exclusion as its source now reports it (null = not excluded). */
    private data class ExclusionChange(
        val exclusion: NewAttribute?,
        val ownedValues: Set<String>?,
        val source: Source,
    )
}
