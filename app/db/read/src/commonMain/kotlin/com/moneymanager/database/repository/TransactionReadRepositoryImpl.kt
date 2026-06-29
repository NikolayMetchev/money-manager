@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.mapper.AccountBalanceMapper
import com.moneymanager.database.mapper.AccountRowMapper
import com.moneymanager.database.mapper.TransferMapper
import com.moneymanager.database.mapper.TransferMissingCompanionMapper
import com.moneymanager.database.sql.read.MoneyManagerDatabase
import com.moneymanager.domain.model.AccountBalance
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AccountRow
import com.moneymanager.domain.model.AttributeType
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.PageWithTargetIndex
import com.moneymanager.domain.model.PagingInfo
import com.moneymanager.domain.model.PagingResult
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferAttribute
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.TransferMissingCompanion
import com.moneymanager.domain.repository.TransactionReadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.time.Instant

class TransactionReadRepositoryImpl(
    database: MoneyManagerDatabase,
) : TransactionReadRepository {
    private val transferSelectQueries = database.transferSelectQueries
    private val transferAttributeSelectQueries = database.transferAttributeSelectQueries

    private fun loadAttributesForTransfers(transfers: List<Transfer>): List<Transfer> {
        if (transfers.isEmpty()) return transfers
        val ids = transfers.map { it.id.id }
        val attributesByTransferId =
            transferAttributeSelectQueries
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
        transferSelectQueries
            .selectById(id, TransferMapper::mapRaw)
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { loadAttributesForTransfer(it) }

    override fun getTransactionsByAccount(accountId: AccountId): Flow<List<Transfer>> =
        transferSelectQueries
            .selectByAccount(accountId.id, accountId.id, TransferMapper::mapRaw)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { loadAttributesForTransfers(it) }

    override fun getTransactionsByDateRange(
        startDate: Instant,
        endDate: Instant,
    ): Flow<List<Transfer>> =
        transferSelectQueries
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
        transferSelectQueries
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
        transferSelectQueries
            .selectTransfersMissingCompanion(
                matchAttributeName,
                matchValuePattern,
                linkAttributeName,
                TransferMissingCompanionMapper::mapRaw,
            ).asFlow()
            .mapToList(Dispatchers.Default)

    override fun getAccountBalances(): Flow<List<AccountBalance>> =
        transferSelectQueries
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
                transferSelectQueries
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
                transferSelectQueries
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
                transferSelectQueries
                    .selectById(transactionId.id, TransferMapper::mapRaw)
                    .executeAsOneOrNull()
                    ?: throw IllegalArgumentException("Transaction not found: $transactionId")

            // Get the row position of the transaction (0-indexed, sorted by timestamp DESC)
            val rowPosition =
                transferSelectQueries
                    .getTransactionRowPosition(
                        accountId = accountId.id,
                        targetTimestamp = transaction.timestamp.toEpochMilliseconds(),
                        targetId = transactionId.id,
                    ).executeAsOne()

            // Get total count to know if there are more items
            val totalCount =
                transferSelectQueries
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
                transferSelectQueries
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
}
