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
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.PageWithTargetIndex
import com.moneymanager.domain.model.PagingInfo
import com.moneymanager.domain.model.PagingResult
import com.moneymanager.domain.model.SourceRecorder
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.DeviceRepository
import com.moneymanager.domain.repository.TransactionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.time.Instant
import kotlin.uuid.Uuid

class TransactionRepositoryImpl(
    private val database: MoneyManagerDatabaseWrapper,
    private val deviceRepository: DeviceRepository,
) : TransactionRepository {
    private val transferQueries = database.transferQueries
    private val transactionIdQueries = database.transactionIdQueries
    private val transferAttributeQueries = database.transferAttributeQueries
    private val transferAttributeAuditQueries = database.transferAttributeAuditQueries
    private val transferSourceQueries = database.transferSourceQueries

    override fun getTransactionById(id: Uuid): Flow<Transfer?> =
        transferQueries.selectById(id.toString(), TransferMapper::mapRaw)
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)

    override fun getTransactionsByAccount(accountId: AccountId): Flow<List<Transfer>> =
        transferQueries.selectByAccount(accountId.id, accountId.id, TransferMapper::mapRaw)
            .asFlow()
            .mapToList(Dispatchers.Default)

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
                ) { id, timestamp, description, accountId_, currencyId, transactionAmount, runningBalance,
                    currency_id, currency_code, currency_name, currency_scaleFactor, sourceAccountId, targetAccountId,
                    ->
                    AccountRowMapper.mapRaw(
                        id,
                        timestamp,
                        description,
                        accountId_,
                        currencyId,
                        transactionAmount,
                        runningBalance,
                        currency_id,
                        currency_code,
                        currency_name,
                        currency_scaleFactor,
                        sourceAccountId,
                        targetAccountId,
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
                ) { id, timestamp, description, accountId_, currencyId, transactionAmount, runningBalance,
                    currency_id, currency_code, currency_name, currency_scaleFactor, sourceAccountId, targetAccountId,
                    ->
                    AccountRowMapper.mapRaw(
                        id,
                        timestamp,
                        description,
                        accountId_,
                        currencyId,
                        transactionAmount,
                        runningBalance,
                        currency_id,
                        currency_code,
                        currency_name,
                        currency_scaleFactor,
                        sourceAccountId,
                        targetAccountId,
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
                ) { id, timestamp, description, accountId_, currencyId, transactionAmount, runningBalance,
                    currency_id, currency_code, currency_name, currency_scaleFactor, sourceAccountId, targetAccountId,
                    ->
                    AccountRowMapper.mapRaw(
                        id,
                        timestamp,
                        description,
                        accountId_,
                        currencyId,
                        transactionAmount,
                        runningBalance,
                        currency_id,
                        currency_code,
                        currency_name,
                        currency_scaleFactor,
                        sourceAccountId,
                        targetAccountId,
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

    override suspend fun createTransfersWithAttributesAndSources(
        transfersWithAttributes: List<Pair<Transfer, List<Pair<AttributeTypeId, String>>>>,
        sourceRecorder: SourceRecorder,
        onProgress: (suspend (created: Int, total: Int) -> Unit)?,
    ): Unit =
        withContext(Dispatchers.Default) {
            val total = transfersWithAttributes.size

            // Process in batches of 1000 to avoid holding transaction too long
            val batchSize = 1000
            var created = 0

            for (batchStart in transfersWithAttributes.indices step batchSize) {
                val batchEnd = minOf(batchStart + batchSize, transfersWithAttributes.size)
                val batch = transfersWithAttributes.subList(batchStart, batchEnd)

                transferQueries.transaction {
                    // Enable creation mode so attribute triggers record audit but don't bump revision.
                    // This allows initial attributes to be recorded at revision 1.
                    database.beginCreationMode()
                    try {
                        batch.forEach { (transfer, attributes) ->
                            // Create transfer (triggers INSERT audit)
                            transactionIdQueries.insert(transfer.id.toString())
                            transferQueries.insert(
                                id = transfer.id.toString(),
                                revisionId = transfer.revisionId,
                                timestamp = transfer.timestamp.toEpochMilliseconds(),
                                description = transfer.description,
                                sourceAccountId = transfer.sourceAccountId.id,
                                targetAccountId = transfer.targetAccountId.id,
                                currencyId = transfer.amount.currency.id.toString(),
                                amount = transfer.amount.amount,
                            )

                            // Insert attributes (creation mode records audit at rev 1 without bumping)
                            attributes.forEach { (typeId, value) ->
                                transferAttributeQueries.insert(
                                    transactionId = transfer.id.toString(),
                                    attributeTypeId = typeId.id,
                                    attributeValue = value,
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

    override suspend fun updateTransfer(transfer: Transfer): Unit =
        withContext(Dispatchers.Default) {
            transferQueries.update(
                timestamp = transfer.timestamp.toEpochMilliseconds(),
                description = transfer.description,
                sourceAccountId = transfer.sourceAccountId.id,
                targetAccountId = transfer.targetAccountId.id,
                currencyId = transfer.amount.currency.id.toString(),
                amount = transfer.amount.amount,
                id = transfer.id.toString(),
            )
        }

    override suspend fun updateTransferAndAttributes(
        transfer: Transfer?,
        deletedAttributeIds: Set<Long>,
        updatedAttributes: Map<Long, Pair<AttributeTypeId, String>>,
        newAttributes: List<Pair<AttributeTypeId, String>>,
        transactionId: TransferId,
    ): Unit =
        withContext(Dispatchers.Default) {
            val hasAttributeChanges =
                deletedAttributeIds.isNotEmpty() ||
                    updatedAttributes.isNotEmpty() ||
                    newAttributes.isNotEmpty()

            transferQueries.transaction {
                // Step 1: Update transfer if provided (bumps revision via trigger)
                if (transfer != null) {
                    transferQueries.update(
                        timestamp = transfer.timestamp.toEpochMilliseconds(),
                        description = transfer.description,
                        sourceAccountId = transfer.sourceAccountId.id,
                        targetAccountId = transfer.targetAccountId.id,
                        currencyId = transfer.amount.currency.id.toString(),
                        amount = transfer.amount.amount,
                        id = transfer.id.toString(),
                    )
                } else if (hasAttributeChanges) {
                    // No transfer change but has attribute changes - need to bump revision first
                    transferQueries.bumpRevisionOnly(transactionId.id.toString())
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
                        updatedAttributes.forEach { (id, pair) ->
                            val (typeId, value) = pair
                            // Check if type changed (requires delete + insert)
                            val current = transferAttributeQueries.selectById(id).executeAsOneOrNull()
                            if (current != null && current.attributeTypeId != typeId.id) {
                                // Type changed: delete and recreate
                                transferAttributeQueries.deleteById(id)
                                transferAttributeQueries.insert(
                                    transactionId = transactionId.id.toString(),
                                    attributeTypeId = typeId.id,
                                    attributeValue = value,
                                )
                            } else {
                                // Only value changed
                                transferAttributeQueries.updateValue(value, id)
                            }
                        }

                        // Insert new attributes
                        newAttributes.forEach { (typeId, value) ->
                            transferAttributeQueries.insert(
                                transactionId = transactionId.id.toString(),
                                attributeTypeId = typeId.id,
                                attributeValue = value,
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
                transferQueries.selectById(id.id.toString()).executeAsOne().revisionId
            }
        }

    override suspend fun deleteTransaction(id: Uuid): Unit =
        withContext(Dispatchers.Default) {
            transferQueries.delete(id.toString())
        }
}
