@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.database.mapper.AccountBalanceMapper
import com.moneymanager.database.mapper.AccountRowMapper
import com.moneymanager.database.mapper.TransferMapper
import com.moneymanager.domain.model.AccountBalance
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AccountRow
import com.moneymanager.domain.model.AttributeType
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.PageWithTargetIndex
import com.moneymanager.domain.model.PagingInfo
import com.moneymanager.domain.model.PagingResult
import com.moneymanager.domain.model.SourceRecorder
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferAttribute
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.TransactionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.time.Instant
import kotlin.uuid.Uuid

class TransactionRepositoryImpl(
    private val database: MoneyManagerDatabaseWrapper,
) : TransactionRepository {
    private val transferQueries = database.transferQueries
    private val transactionIdQueries = database.transactionIdQueries
    private val transferAttributeQueries = database.transferAttributeQueries

    private fun loadAttributesForTransfers(transfers: List<Transfer>): List<Transfer> {
        if (transfers.isEmpty()) return transfers
        val ids = transfers.map { it.id.id.toString() }
        val attributesByTransferId =
            transferAttributeQueries.selectByTransactionIds(ids)
                .executeAsList()
                .groupBy { TransferId(Uuid.parse(it.transaction_id)) }
                .mapValues { (_, rows) ->
                    rows.map { row ->
                        TransferAttribute(
                            id = row.id,
                            transactionId = TransferId(Uuid.parse(row.transaction_id)),
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

    override fun getTransactionById(id: Uuid): Flow<Transfer?> =
        transferQueries.selectById(id.toString(), TransferMapper::mapRaw)
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { loadAttributesForTransfer(it) }

    override fun getTransactionsByAccount(accountId: AccountId): Flow<List<Transfer>> =
        transferQueries.selectByAccount(accountId.id, accountId.id, TransferMapper::mapRaw)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { loadAttributesForTransfers(it) }

    override fun getTransactionsByDateRange(
        startDate: Instant,
        endDate: Instant,
    ): Flow<List<Transfer>> =
        transferQueries.selectByDateRange(
            startDate.toEpochMilliseconds(),
            endDate.toEpochMilliseconds(),
            TransferMapper::mapRaw,
        )
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { loadAttributesForTransfers(it) }

    override fun getTransactionsByAccountAndDateRange(
        accountId: AccountId,
        startDate: Instant,
        endDate: Instant,
    ): Flow<List<Transfer>> =
        transferQueries.selectByAccountAndDateRange(
            accountId.id,
            accountId.id,
            startDate.toEpochMilliseconds(),
            endDate.toEpochMilliseconds(),
            TransferMapper::mapRaw,
        )
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { loadAttributesForTransfers(it) }

    override fun getAccountBalances(): Flow<List<AccountBalance>> =
        transferQueries.selectAllBalances()
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
                transferQueries.selectRunningBalanceByAccountPaginated(
                    accountId.id,
                    pagingInfo?.lastTimestamp?.toEpochMilliseconds(),
                    pagingInfo?.lastId?.toString(),
                    (pageSize + 1).toLong(),
                ) { id, timestamp, description, account_id, transaction_amount, running_balance,
                    currency_id, currency_code, currency_name, currency_scale_factor, source_account_id, target_account_id,
                    ->
                    AccountRowMapper.mapRaw(
                        id,
                        timestamp,
                        description,
                        account_id,
                        transaction_amount,
                        running_balance,
                        currency_id,
                        currency_code,
                        currency_name,
                        currency_scale_factor,
                        source_account_id,
                        target_account_id,
                    )
                }
                    .executeAsList()

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
                transferQueries.selectRunningBalanceByAccountPaginatedBackward(
                    accountId.id,
                    firstTimestamp.toEpochMilliseconds(),
                    firstId.id.toString(),
                    (pageSize + 1).toLong(),
                ) { id, timestamp, description, account_id, transaction_amount, running_balance,
                    currency_id, currency_code, currency_name, currency_scale_factor, source_account_id, target_account_id,
                    ->
                    AccountRowMapper.mapRaw(
                        id,
                        timestamp,
                        description,
                        account_id,
                        transaction_amount,
                        running_balance,
                        currency_id,
                        currency_code,
                        currency_name,
                        currency_scale_factor,
                        source_account_id,
                        target_account_id,
                    )
                }
                    .executeAsList()
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
                transferQueries.selectById(transactionId.id.toString(), TransferMapper::mapRaw)
                    .executeAsOneOrNull()
                    ?: throw IllegalArgumentException("Transaction not found: $transactionId")

            // Get the row position of the transaction (0-indexed, sorted by timestamp DESC)
            val rowPosition =
                transferQueries.getTransactionRowPosition(
                    accountId = accountId.id,
                    targetTimestamp = transaction.timestamp.toEpochMilliseconds(),
                    targetId = transactionId.id.toString(),
                ).executeAsOne()

            // Get total count to know if there are more items
            val totalCount =
                transferQueries.countTransactionsByAccount(accountId.id)
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
                transferQueries.selectRunningBalanceByAccountOffset(
                    accountId = accountId.id,
                    limit = pageSize.toLong(),
                    offset = offset,
                ) { id, timestamp, description, account_id, transaction_amount, running_balance,
                    currency_id, currency_code, currency_name, currency_scale_factor, source_account_id, target_account_id,
                    ->
                    AccountRowMapper.mapRaw(
                        id,
                        timestamp,
                        description,
                        account_id,
                        transaction_amount,
                        running_balance,
                        currency_id,
                        currency_code,
                        currency_name,
                        currency_scale_factor,
                        source_account_id,
                        target_account_id,
                    )
                }
                    .executeAsList()

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
        sourceRecorder: SourceRecorder,
        onProgress: (suspend (created: Int, total: Int) -> Unit)?,
    ): Unit =
        withContext(Dispatchers.Default) {
            val total = transfers.size

            // Process in batches of 1000 to avoid holding transaction too long
            val batchSize = 1000
            var created = 0

            for (batchStart in transfers.indices step batchSize) {
                val batchEnd = minOf(batchStart + batchSize, transfers.size)
                val batch = transfers.subList(batchStart, batchEnd)

                transferQueries.transaction {
                    // Enable creation mode so attribute triggers record audit but don't bump revision.
                    // This allows initial attributes to be recorded at revision 1.
                    database.beginCreationMode()
                    try {
                        batch.forEach { transfer ->
                            // Create transfer (triggers INSERT audit)
                            transactionIdQueries.insert(transfer.id.toString())
                            transferQueries.insert(
                                id = transfer.id.toString(),
                                revision_id = transfer.revisionId,
                                timestamp = transfer.timestamp.toEpochMilliseconds(),
                                description = transfer.description,
                                source_account_id = transfer.sourceAccountId.id,
                                target_account_id = transfer.targetAccountId.id,
                                currency_id = transfer.amount.currency.id.toString(),
                                amount = transfer.amount.amount,
                            )

                            // Insert attributes (creation mode records audit at rev 1 without bumping)
                            newAttributes[transfer.id].orEmpty().forEach { attr ->
                                transferAttributeQueries.insert(
                                    transaction_id = transfer.id.toString(),
                                    attribute_type_id = attr.typeId.id,
                                    attribute_value = attr.value,
                                )
                            }

                            // Record source using strategy pattern
                            sourceRecorder.insert(transfer)
                        }
                    } finally {
                        // Always restore normal trigger behavior
                        database.endCreationMode()
                    }
                }

                created += batch.size
                onProgress?.invoke(created, total)
            }
        }

    override suspend fun updateTransfer(
        transfer: Transfer?,
        deletedAttributeIds: Set<Long>,
        updatedAttributes: Map<Long, NewAttribute>,
        newAttributes: List<NewAttribute>,
        transactionId: TransferId,
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
                        currency_id = transfer.amount.currency.id.toString(),
                        amount = transfer.amount.amount,
                        id = transfer.id.toString(),
                    )
                } else if (hasAttributeChanges) {
                    // No transfer change but has attribute changes - need to bump revision first
                    transferQueries.bumpRevisionOnly(effectiveTransactionId.id.toString())
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
                                    transaction_id = effectiveTransactionId.id.toString(),
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
                                transaction_id = effectiveTransactionId.id.toString(),
                                attribute_type_id = attr.typeId.id,
                                attribute_value = attr.value,
                            )
                        }
                    } finally {
                        database.endCreationMode()
                    }
                }
            }
        }

    override suspend fun bumpRevisionOnly(id: TransferId): Long =
        withContext(Dispatchers.Default) {
            transferQueries.transactionWithResult {
                transferQueries.bumpRevisionOnly(id.id.toString())
                transferQueries.selectById(id.id.toString()).executeAsOne().revision_id
            }
        }

    override suspend fun deleteTransaction(id: Uuid): Unit =
        withContext(Dispatchers.Default) {
            transferQueries.delete(id.toString())
        }
}
