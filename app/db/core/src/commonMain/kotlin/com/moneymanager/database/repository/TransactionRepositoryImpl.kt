@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.database.mapper.AccountBalanceMapper
import com.moneymanager.database.mapper.AccountRowMapper
import com.moneymanager.database.mapper.TransferMapper
import com.moneymanager.database.mapper.TransferMissingCompanionMapper
import com.moneymanager.database.recordSource
import com.moneymanager.domain.model.AccountBalance
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AccountRow
import com.moneymanager.domain.model.AttributeType
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.NewRelationship
import com.moneymanager.domain.model.PageWithTargetIndex
import com.moneymanager.domain.model.PagingInfo
import com.moneymanager.domain.model.PagingResult
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferAttribute
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.TransferMissingCompanion
import com.moneymanager.domain.repository.TransactionRepository
import com.moneymanager.domain.repository.TransferUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.time.Instant

class TransactionRepositoryImpl(
    private val database: MoneyManagerDatabaseWrapper,
    private val deviceId: DeviceId,
) : TransactionRepository {
    private val transferQueries = database.transferQueries
    private val transactionIdQueries = database.transactionIdQueries
    private val transferAttributeQueries = database.transferAttributeQueries
    private val transferRelationshipQueries = database.transferRelationshipQueries
    private val entitySourceQueries = database.entitySourceQueries

    private fun loadAttributesForTransfers(transfers: List<Transfer>): List<Transfer> {
        if (transfers.isEmpty()) return transfers
        val ids = transfers.map { it.id.id }
        val attributesByTransferId =
            transferAttributeQueries
                .selectByTransactionIds(ids)
                .executeAsList()
                .groupBy { TransferId(it.transaction_id) }
                .mapValues { (_, rows) ->
                    rows.map { row ->
                        TransferAttribute(
                            id = row.id,
                            transactionId = TransferId(row.transaction_id),
                            attributeType =
                                AttributeType(
                                    id = AttributeTypeId(row.attribute_type_id),
                                    name = row.attribute_type_name,
                                ),
                            value = row.attribute_value,
                        )
                    }
                }
        return transfers.map { it.copy(attributes = attributesByTransferId[it.id].orEmpty()) }
    }

    private fun loadAttributesForTransfer(transfer: Transfer?): Transfer? {
        if (transfer == null) return null
        return loadAttributesForTransfers(listOf(transfer)).first()
    }

    override fun getTransactionById(id: Long): Flow<Transfer?> =
        transferQueries
            .selectById(id, TransferMapper::mapRaw)
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { loadAttributesForTransfer(it) }

    override fun getTransactionsByAccount(accountId: AccountId): Flow<List<Transfer>> =
        transferQueries
            .selectByAccount(accountId.id, accountId.id, TransferMapper::mapRaw)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { loadAttributesForTransfers(it) }

    override fun getTransactionsByDateRange(
        startDate: Instant,
        endDate: Instant,
    ): Flow<List<Transfer>> =
        transferQueries
            .selectByDateRange(
                startDate.toEpochMilliseconds(),
                endDate.toEpochMilliseconds(),
                TransferMapper::mapRaw,
            ).asFlow()
            .mapToList(Dispatchers.Default)
            .map { loadAttributesForTransfers(it) }

    override fun getTransactionsByAccountAndDateRange(
        accountId: AccountId,
        startDate: Instant,
        endDate: Instant,
    ): Flow<List<Transfer>> =
        transferQueries
            .selectByAccountAndDateRange(
                accountId.id,
                accountId.id,
                startDate.toEpochMilliseconds(),
                endDate.toEpochMilliseconds(),
                TransferMapper::mapRaw,
            ).asFlow()
            .mapToList(Dispatchers.Default)
            .map { loadAttributesForTransfers(it) }

    override fun getTransfersMissingCompanion(
        matchAttributeName: String,
        matchValuePattern: String,
        linkAttributeName: String,
    ): Flow<List<TransferMissingCompanion>> =
        transferQueries
            .selectTransfersMissingCompanion(
                matchAttributeName,
                matchValuePattern,
                linkAttributeName,
                TransferMissingCompanionMapper::mapRaw,
            ).asFlow()
            .mapToList(Dispatchers.Default)

    override fun getAccountBalances(): Flow<List<AccountBalance>> =
        transferQueries
            .selectAllBalances()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map(AccountBalanceMapper::mapList)

    override suspend fun getRunningBalanceByAccountPaginated(
        accountId: AccountId,
        pageSize: Int,
        pagingInfo: PagingInfo?,
    ): PagingResult<AccountRow> =
        withContext(Dispatchers.Default) {
            val items =
                transferQueries
                    .selectRunningBalanceByAccountPaginated(
                        accountId.id,
                        pagingInfo?.lastTimestamp?.toEpochMilliseconds(),
                        pagingInfo?.lastId?.id,
                        (pageSize + 1).toLong(),
                        AccountRowMapper::mapRaw,
                    ).executeAsList()

            val hasMore = items.size > pageSize
            val pageItems = if (hasMore) items.take(pageSize) else items

            val nextPagingInfo =
                if (hasMore && pageItems.isNotEmpty()) {
                    val lastItem = pageItems.last()
                    PagingInfo(
                        lastTimestamp = lastItem.timestamp,
                        lastId = lastItem.transactionId,
                        hasMore = true,
                    )
                } else {
                    PagingInfo(
                        lastTimestamp = null,
                        lastId = null,
                        hasMore = false,
                    )
                }

            PagingResult(
                items = pageItems,
                pagingInfo = nextPagingInfo,
            )
        }

    override suspend fun getRunningBalanceByAccountPaginatedBackward(
        accountId: AccountId,
        pageSize: Int,
        firstTimestamp: Instant,
        firstId: TransactionId,
    ): PagingResult<AccountRow> =
        withContext(Dispatchers.Default) {
            val items =
                transferQueries
                    .selectRunningBalanceByAccountPaginatedBackward(
                        accountId.id,
                        firstTimestamp.toEpochMilliseconds(),
                        firstId.id,
                        (pageSize + 1).toLong(),
                        AccountRowMapper::mapRaw,
                    ).executeAsList()
                    // Reverse to get correct display order (newest first)
                    .reversed()

            val hasMore = items.size > pageSize
            val pageItems = if (hasMore) items.drop(1) else items

            val nextPagingInfo =
                if (hasMore && pageItems.isNotEmpty()) {
                    val firstItem = pageItems.first()
                    PagingInfo(
                        lastTimestamp = firstItem.timestamp,
                        lastId = firstItem.transactionId,
                        hasMore = true,
                    )
                } else {
                    PagingInfo(
                        lastTimestamp = null,
                        lastId = null,
                        hasMore = false,
                    )
                }

            PagingResult(
                items = pageItems,
                pagingInfo = nextPagingInfo,
            )
        }

    override suspend fun getPageContainingTransaction(
        accountId: AccountId,
        transactionId: TransferId,
        pageSize: Int,
    ): PageWithTargetIndex<AccountRow> =
        withContext(Dispatchers.Default) {
            // First, get the transaction to find its timestamp
            val transaction =
                transferQueries
                    .selectById(transactionId.id, TransferMapper::mapRaw)
                    .executeAsOneOrNull()
                    ?: throw IllegalArgumentException("Transaction not found: $transactionId")

            // Get the row position of the transaction (0-indexed, sorted by timestamp DESC)
            val rowPosition =
                transferQueries
                    .getTransactionRowPosition(
                        accountId = accountId.id,
                        targetTimestamp = transaction.timestamp.toEpochMilliseconds(),
                        targetId = transactionId.id,
                    ).executeAsOne()

            // Get total count to know if there are more items
            val totalCount =
                transferQueries
                    .countTransactionsByAccount(accountId.id)
                    .executeAsOne()

            // Calculate offset to center the transaction in the page
            // Try to put the transaction in the middle
            val halfPage = pageSize / 2
            val idealOffset = (rowPosition - halfPage).coerceAtLeast(0)

            // Adjust offset if we're near the end to fill the page
            val maxOffset = (totalCount - pageSize).coerceAtLeast(0)
            val offset = idealOffset.coerceAtMost(maxOffset)

            // Load the page
            val items =
                transferQueries
                    .selectRunningBalanceByAccountOffset(
                        accountId = accountId.id,
                        limit = pageSize.toLong(),
                        offset = offset,
                        mapper = AccountRowMapper::mapRaw,
                    ).executeAsList()

            // Find the target transaction's index within the loaded page
            val targetIndex =
                items.indexOfFirst { it.transactionId.id == transactionId.id }

            // Calculate if there are more items after this page
            val hasMore = (offset + items.size) < totalCount

            val pagingInfo =
                if (hasMore && items.isNotEmpty()) {
                    val lastItem = items.last()
                    PagingInfo(
                        lastTimestamp = lastItem.timestamp,
                        lastId = lastItem.transactionId,
                        hasMore = true,
                    )
                } else {
                    PagingInfo(
                        lastTimestamp = null,
                        lastId = null,
                        hasMore = false,
                    )
                }

            PageWithTargetIndex(
                items = items,
                targetIndex = targetIndex,
                pagingInfo = pagingInfo,
                hasPrevious = offset > 0,
            )
        }

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

                transferQueries.transaction {
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

            transferQueries.transaction {
                // Step 1: Update transfer if provided (bumps revision via trigger)
                if (transfer != null) {
                    transferQueries.update(
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
                    transferQueries.bumpRevisionOnly(effectiveTransactionId.id)
                }

                // Step 2: Apply attribute changes in creation mode (record audit but don't bump again)
                if (hasAttributeChanges) {
                    database.beginCreationMode()
                    try {
                        // Delete attributes
                        deletedAttributeIds.forEach { id ->
                            transferAttributeQueries.deleteById(id)
                        }

                        // Update attributes
                        updatedAttributes.forEach { (id, attr) ->
                            // Check if type changed (requires delete + insert)
                            val current = transferAttributeQueries.selectById(id).executeAsOneOrNull()
                            if (current != null && current.attribute_type_id != attr.typeId.id) {
                                // Type changed: delete and recreate
                                transferAttributeQueries.deleteById(id)
                                transferAttributeQueries.insert(
                                    transaction_id = effectiveTransactionId.id,
                                    attribute_type_id = attr.typeId.id,
                                    attribute_value = attr.value,
                                )
                            } else {
                                // Only value changed
                                transferAttributeQueries.updateValue(attr.value, id)
                            }
                        }

                        // Insert new attributes
                        newAttributes.forEach { attr ->
                            transferAttributeQueries.insert(
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
                val persisted = transferQueries.selectById(effectiveTransactionId.id, TransferMapper::mapRaw).executeAsOne()
                entitySourceQueries.recordSource(
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
            transferQueries.transactionWithResult {
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
                    transferQueries.update(
                        timestamp = updatedTransfer.timestamp.toEpochMilliseconds(),
                        description = updatedTransfer.description,
                        source_account_id = updatedTransfer.sourceAccountId.id,
                        target_account_id = updatedTransfer.targetAccountId.id,
                        currency_id = updatedTransfer.amount.currency.id.id,
                        amount = updatedTransfer.amount.amount,
                        id = updatedTransfer.id.id,
                    )
                    if (update.newAttributes.isNotEmpty()) {
                        database.beginCreationMode()
                        try {
                            update.newAttributes.forEach { attr ->
                                transferAttributeQueries.insert(
                                    transaction_id = updatedTransfer.id.id,
                                    attribute_type_id = attr.typeId.id,
                                    attribute_value = attr.value,
                                )
                            }
                        } finally {
                            database.endCreationMode()
                        }
                    }
                    // Record the source against the new revision.
                    val persisted = transferQueries.selectById(updatedTransfer.id.id, TransferMapper::mapRaw).executeAsOne()
                    entitySourceQueries.recordSource(
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
            transactionIdQueries.insert()
            val generatedId = transactionIdQueries.lastInsertedId().executeAsOne()
            val realId = TransferId(generatedId)
            createdIds += realId
            idMap[transfer.id] = realId
            transferQueries.insert(
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
                transferAttributeQueries.insert(
                    transaction_id = generatedId,
                    attribute_type_id = attr.typeId.id,
                    attribute_value = attr.value,
                )
            }
            entitySourceQueries.recordSource(deviceId, EntityType.TRANSFER, realId.id, transfer.revisionId, sources[index])
        }
        // Pass 2: insert relationships now that every in-batch transfer has a real id. The owning transfer
        // is id1; the related transfer (id2) may be a pre-existing transfer (reconciliation) or a sibling
        // created in this same batch (a fee transfer), resolved via [idMap].
        transfers.forEach { transfer ->
            val id1 = idMap.getValue(transfer.id).id
            newRelationships[transfer.id].orEmpty().forEach { rel ->
                val id2 = idMap[rel.relatedTransferId]?.id ?: rel.relatedTransferId.id
                transferRelationshipQueries.insert(
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
            transferQueries.delete(id)
        }
}
