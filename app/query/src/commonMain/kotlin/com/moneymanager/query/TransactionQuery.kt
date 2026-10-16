package com.moneymanager.query

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AccountRow
import com.moneymanager.domain.model.PagingInfo
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.TransactionReadRepository
import kotlin.time.Instant

/**
 * One account's transactions, newest first, with their running balances, a page at a time in either direction.
 * Reads the running-balance materialized view, so a caller that needs it current refreshes it first (see
 * [AccountQuery]).
 */
class TransactionQuery(
    private val transactionRepository: TransactionReadRepository,
) {
    /**
     * Up to [limit] rows of [accountId] with `from <= timestamp < to`, continuing after [cursor] when given.
     * Pass the same [from] and [to] with a cursor; [to] places the first page when there's no cursor, and bounds
     * [TransactionPage.previous] either way.
     */
    suspend fun page(
        accountId: AccountId,
        limit: Int,
        from: Instant? = null,
        to: Instant? = null,
        cursor: TransactionCursor? = null,
    ): TransactionPage {
        require(limit > 0) { "The page size must be positive, not $limit" }
        // The query keeps rows before (timestamp, id); no row has id 0, so starting at (to, 0) keeps timestamp < to.
        val start = cursor ?: to?.let { TransactionCursor(it, 0) }
        // One row past the page says whether another page exists inside the range.
        val fetched = transactionRepository.getRunningBalanceByAccountPaginated(accountId, limit + 1, start?.toPagingInfo()).items
        val inRange = if (from == null) fetched else fetched.takeWhile { it.timestamp >= from }
        val rows = inRange.take(limit)
        val next = rows.lastOrNull()?.takeIf { inRange.size > limit }?.cursor()
        // Nothing is newer than the first page; otherwise look one row past the page's newest position.
        val previous = start?.let { rows.firstOrNull()?.cursor() ?: it }?.takeIf { hasNewer(accountId, it, to) }
        return TransactionPage(rows, next, previous)
    }

    /**
     * Up to [limit] rows newer than [cursor] (the newest row a caller already has), still newest first, keeping
     * `timestamp < to`. The page's [TransactionPage.next] is its oldest row, so it always joins up with [cursor].
     */
    suspend fun pageBefore(
        accountId: AccountId,
        limit: Int,
        cursor: TransactionCursor,
        to: Instant? = null,
    ): TransactionPage {
        require(limit > 0) { "The page size must be positive, not $limit" }
        val fetched =
            transactionRepository.getRunningBalanceByAccountPaginatedBackward(accountId, limit, cursor.timestamp, TransferId(cursor.id))
        val rows = if (to == null) fetched.items else fetched.items.dropWhile { it.timestamp >= to }
        val previous = rows.firstOrNull()?.takeIf { rows.size == fetched.items.size && fetched.pagingInfo.hasMore }?.cursor()
        return TransactionPage(rows, rows.lastOrNull()?.cursor() ?: cursor, previous)
    }

    /** About [limit] rows of [accountId] around [transferId], whatever their dates. */
    suspend fun pageContaining(
        accountId: AccountId,
        transferId: TransferId,
        limit: Int,
    ): TransactionPage {
        require(limit > 0) { "The page size must be positive, not $limit" }
        val page = transactionRepository.getPageContainingTransaction(accountId, transferId, limit)
        return TransactionPage(
            rows = page.items,
            next =
                page.items
                    .lastOrNull()
                    ?.takeIf { page.pagingInfo.hasMore }
                    ?.cursor(),
            previous =
                page.items
                    .firstOrNull()
                    ?.takeIf { page.hasPrevious }
                    ?.cursor(),
        )
    }

    private suspend fun hasNewer(
        accountId: AccountId,
        position: TransactionCursor,
        to: Instant?,
    ): Boolean {
        val newer =
            transactionRepository.getRunningBalanceByAccountPaginatedBackward(
                accountId,
                1,
                position.timestamp,
                TransferId(position.id),
            )
        return newer.items.any { to == null || it.timestamp < to }
    }
}

data class TransactionPage(
    val rows: List<AccountRow>,
    /** Where the next (older) page starts, or null when this is the last one. */
    val next: TransactionCursor?,
    /** Where the previous (newer) page starts, or null when this is the newest one. */
    val previous: TransactionCursor? = null,
)

/** A position in an account's newest-first transaction list. Its [encode]d form is opaque to callers. */
data class TransactionCursor(
    val timestamp: Instant,
    val id: Long,
) {
    fun encode(): String = "${timestamp.toEpochMilliseconds()}.$id"

    internal fun toPagingInfo() = PagingInfo(timestamp, TransferId(id), hasMore = true)

    companion object {
        /** @throws IllegalArgumentException if [value] isn't a cursor [encode] produced */
        fun decode(value: String): TransactionCursor {
            val parts = value.split('.')
            val millis = parts.getOrNull(0)?.toLongOrNull()
            val id = parts.getOrNull(1)?.toLongOrNull()
            require(parts.size == 2 && millis != null && id != null) { "Not a transactions cursor: '$value'" }
            return TransactionCursor(Instant.fromEpochMilliseconds(millis), id)
        }
    }
}

/** This row's position, for continuing a page before or after it. */
fun AccountRow.cursor(): TransactionCursor = TransactionCursor(timestamp, transactionId.id)
