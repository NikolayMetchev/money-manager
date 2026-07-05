@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.repository

import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.database.mapper.TransferMapper
import com.moneymanager.database.recordSource
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.NewRelationship
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.TransactionReadRepository
import com.moneymanager.domain.repository.TransactionWriteRepository
import com.moneymanager.domain.repository.TransferUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TransactionWriteRepositoryImpl(
    private val database: MoneyManagerDatabaseWrapper,
    private val deviceId: DeviceId,
    reader: TransactionReadRepository,
) : TransactionWriteRepository,
    TransactionReadRepository by reader {
    private val transferSelectQueries = database.transferSelectQueries
    private val transferWriteQueries = database.transferWriteQueries
    private val transactionIdWriteQueries = database.transactionIdWriteQueries
    private val transferAttributeSelectQueries = database.transferAttributeSelectQueries
    private val transferAttributeWriteQueries = database.transferAttributeWriteQueries
    private val transferRelationshipWriteQueries = database.transferRelationshipWriteQueries

    override suspend fun createTransfers(
        transfers: List<Transfer>,
        newAttributes: Map<TransferId, List<NewAttribute>>,
        sources: List<Source>,
        batchSize: Int,
        onProgress: (suspend (created: Int, total: Int) -> Unit)?,
    ): List<TransferId> =
        withContext(Dispatchers.Default) {
            require(sources.size == transfers.size) { "sources must align 1:1 with transfers" }
            val total = transfers.size
            val createdIds = mutableListOf<TransferId>()

            // Use caller-provided batching strategy.
            val effectiveBatchSize = batchSize.coerceAtLeast(1)
            var created = 0

            for (batchStart in transfers.indices step effectiveBatchSize) {
                val batchEnd = minOf(batchStart + effectiveBatchSize, transfers.size)
                val batch = transfers.subList(batchStart, batchEnd)
                val batchSources = sources.subList(batchStart, batchEnd)

                transferWriteQueries.transaction {
                    // Enable creation mode so attribute triggers record audit but don't bump revision.
                    // This allows initial attributes to be recorded at revision 1.
                    database.beginCreationMode()
                    try {
                        createdIds += insertNewTransfers(batch, newAttributes, newRelationships = emptyMap(), batchSources)
                    } finally {
                        // Always restore normal trigger behavior
                        database.endCreationMode()
                    }
                }

                created += batch.size
                onProgress?.invoke(created, total)
            }

            createdIds
        }

    override suspend fun updateTransfer(
        transfer: Transfer?,
        deletedAttributeIds: Set<Long>,
        updatedAttributes: Map<Long, NewAttribute>,
        newAttributes: List<NewAttribute>,
        transactionId: TransferId,
        source: Source,
    ): Unit =
        withContext(Dispatchers.Default) {
            val hasAttributeChanges =
                deletedAttributeIds.isNotEmpty() ||
                    updatedAttributes.isNotEmpty() ||
                    newAttributes.isNotEmpty()

            // Use transfer.id when available, fall back to transactionId for attribute-only updates
            val effectiveTransactionId = transfer?.id ?: transactionId

            transferWriteQueries.transaction {
                // Step 1: Update transfer if provided (bumps revision via trigger)
                if (transfer != null) {
                    transferWriteQueries.update(
                        timestamp = transfer.timestamp.toEpochMilliseconds(),
                        description = transfer.description,
                        source_account_id = transfer.sourceAccountId.id,
                        target_account_id = transfer.targetAccountId.id,
                        currency_id = transfer.amount.currency.id.id,
                        amount = transfer.amount.amount,
                        id = transfer.id.id,
                    )
                } else if (hasAttributeChanges) {
                    // No transfer change but has attribute changes - need to bump revision first
                    transferWriteQueries.bumpRevisionOnly(effectiveTransactionId.id)
                }

                // Step 2: Apply attribute changes in creation mode (record audit but don't bump again)
                if (hasAttributeChanges) {
                    database.beginCreationMode()
                    try {
                        // Delete attributes
                        deletedAttributeIds.forEach { id ->
                            transferAttributeWriteQueries.deleteById(id)
                        }

                        // Update attributes
                        updatedAttributes.forEach { (id, attr) ->
                            // Check if type changed (requires delete + insert)
                            val current = transferAttributeSelectQueries.selectById(id).executeAsOneOrNull()
                            if (current != null && current.attribute_type_id != attr.typeId.id) {
                                // Type changed: delete and recreate
                                transferAttributeWriteQueries.deleteById(id)
                                transferAttributeWriteQueries.insert(
                                    transaction_id = effectiveTransactionId.id,
                                    attribute_type_id = attr.typeId.id,
                                    attribute_value = attr.value,
                                )
                            } else {
                                // Only value changed
                                transferAttributeWriteQueries.updateValue(attr.value, id)
                            }
                        }

                        // Insert new attributes
                        newAttributes.forEach { attr ->
                            transferAttributeWriteQueries.insert(
                                transaction_id = effectiveTransactionId.id,
                                attribute_type_id = attr.typeId.id,
                                attribute_value = attr.value,
                            )
                        }
                    } finally {
                        database.endCreationMode()
                    }
                }

                // Record the source against the new revision.
                val persisted = transferSelectQueries.selectById(effectiveTransactionId.id, TransferMapper::mapRaw).executeAsOne()
                database.recordSource(
                    deviceId,
                    EntityType.TRANSFER,
                    persisted.id.id,
                    persisted.revisionId,
                    source,
                )
            }
        }

    override suspend fun importTransfers(
        transfers: List<Transfer>,
        newAttributes: Map<TransferId, List<NewAttribute>>,
        newRelationships: Map<TransferId, List<NewRelationship>>,
        sources: List<Source>,
        updates: List<TransferUpdate>,
        updateSources: List<Source>,
    ): List<TransferId> =
        withContext(Dispatchers.Default) {
            require(sources.size == transfers.size) { "sources must align 1:1 with transfers" }
            require(updateSources.size == updates.size) { "updateSources must align 1:1 with updates" }
            transferWriteQueries.transactionWithResult {
                val createdIds = mutableListOf<TransferId>()

                // Create new transfers in creation mode so initial attributes don't bump revision.
                if (transfers.isNotEmpty()) {
                    database.beginCreationMode()
                    try {
                        createdIds += insertNewTransfers(transfers, newAttributes, newRelationships, sources)
                    } finally {
                        database.endCreationMode()
                    }
                }

                // Apply updates: the field update bumps the revision; new attributes are added in
                // creation mode so they don't bump it again.
                updates.forEachIndexed { index, update ->
                    val updatedTransfer = update.transfer
                    transferWriteQueries.update(
                        timestamp = updatedTransfer.timestamp.toEpochMilliseconds(),
                        description = updatedTransfer.description,
                        source_account_id = updatedTransfer.sourceAccountId.id,
                        target_account_id = updatedTransfer.targetAccountId.id,
                        currency_id = updatedTransfer.amount.currency.id.id,
                        amount = updatedTransfer.amount.amount,
                        id = updatedTransfer.id.id,
                    )
                    if (update.newAttributes.isNotEmpty()) {
                        // The existing transfer may already carry some of these attribute types (e.g. a
                        // cross-source reconciliation added one, so a re-import of an otherwise-unchanged
                        // row is classified UPDATED). Upsert by (transaction_id, attribute_type_id) —
                        // update a changed value, insert a genuinely new type — instead of blindly
                        // inserting, which would violate the UNIQUE(transaction_id, attribute_type_id)
                        // constraint on the types already present.
                        val existingByType =
                            transferAttributeSelectQueries
                                .selectByTransaction(updatedTransfer.id.id) { rowId, _, typeId, value, _, _ ->
                                    typeId to (rowId to value)
                                }.executeAsList()
                                .toMap()
                        database.beginCreationMode()
                        try {
                            update.newAttributes.forEach { attr ->
                                val existing = existingByType[attr.typeId.id]
                                when {
                                    existing == null ->
                                        transferAttributeWriteQueries.insert(
                                            transaction_id = updatedTransfer.id.id,
                                            attribute_type_id = attr.typeId.id,
                                            attribute_value = attr.value,
                                        )
                                    existing.second != attr.value ->
                                        transferAttributeWriteQueries.updateValue(attr.value, existing.first)
                                    // Same value already present: nothing to do.
                                }
                            }
                        } finally {
                            database.endCreationMode()
                        }
                    }
                    // Record the source against the new revision.
                    val persisted = transferSelectQueries.selectById(updatedTransfer.id.id, TransferMapper::mapRaw).executeAsOne()
                    database.recordSource(
                        deviceId,
                        EntityType.TRANSFER,
                        persisted.id.id,
                        persisted.revisionId,
                        updateSources[index],
                    )
                }

                createdIds
            }
        }

    /**
     * Inserts new transfers with their attributes and records their source. Must be called inside an
     * open transaction with creation mode active. Returns the generated ids in input order.
     */
    private fun insertNewTransfers(
        transfers: List<Transfer>,
        newAttributes: Map<TransferId, List<NewAttribute>>,
        newRelationships: Map<TransferId, List<NewRelationship>>,
        sources: List<Source>,
    ): List<TransferId> {
        val createdIds = mutableListOf<TransferId>()
        // Pass 1: insert transfers + attributes and record source, building the input(temp) id -> real id
        // map so relationships can reference siblings created in the same batch (e.g. a fee transfer).
        val idMap = mutableMapOf<TransferId, TransferId>()
        transfers.forEachIndexed { index, transfer ->
            transactionIdWriteQueries.insert()
            val generatedId = transactionIdWriteQueries.lastInsertedId().executeAsOne()
            val realId = TransferId(generatedId)
            createdIds += realId
            idMap[transfer.id] = realId
            transferWriteQueries.insert(
                id = generatedId,
                revision_id = transfer.revisionId,
                timestamp = transfer.timestamp.toEpochMilliseconds(),
                description = transfer.description,
                source_account_id = transfer.sourceAccountId.id,
                target_account_id = transfer.targetAccountId.id,
                currency_id = transfer.amount.currency.id.id,
                amount = transfer.amount.amount,
            )
            newAttributes[transfer.id].orEmpty().forEach { attr ->
                transferAttributeWriteQueries.insert(
                    transaction_id = generatedId,
                    attribute_type_id = attr.typeId.id,
                    attribute_value = attr.value,
                )
            }
            database.recordSource(deviceId, EntityType.TRANSFER, realId.id, transfer.revisionId, sources[index])
        }
        // Pass 2: insert relationships now that every in-batch transfer has a real id. The owning transfer
        // is id1; the related transfer (id2) may be a pre-existing transfer (reconciliation) or a sibling
        // created in this same batch (a fee transfer), resolved via [idMap].
        transfers.forEach { transfer ->
            val id1 = idMap.getValue(transfer.id).id
            newRelationships[transfer.id].orEmpty().forEach { rel ->
                val id2 = idMap[rel.relatedTransferId]?.id ?: rel.relatedTransferId.id
                transferRelationshipWriteQueries.insert(
                    id1 = id1,
                    id2 = id2,
                    relationship_type_id = rel.typeId.id,
                )
            }
        }
        return createdIds
    }

    override suspend fun deleteTransaction(id: Long): Unit =
        withContext(Dispatchers.Default) {
            transferWriteQueries.delete(id)
        }
}
