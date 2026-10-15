package com.moneymanager.query

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AccountRow
import com.moneymanager.domain.model.PagingInfo
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.TransactionReadRepository
import kotlin.time.Instant

/**
 * One account's transactions, newest first, with their running balances, a page at a time. Reads the
 * running-balance materialized view, so a caller that needs it current refreshes it first (see [AccountQuery]).
 */
class TransactionQuery(
    private val transactionRepository: TransactionReadRepository,
) {
    /**
     * Up to [limit] rows of [accountId] with `from <= timestamp < to`, continuing after [cursor] when given.
     * Pass the same [from] with a cursor; [to] only places the first page, so a cursor overrides it.
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
        val start =
            cursor?.let { PagingInfo(it.timestamp, TransferId(it.id), hasMore = true) }
                ?: to?.let { PagingInfo(it, TransferId(0), hasMore = true) }
        // One row past the page says whether another page exists inside the range.
        val fetched = transactionRepository.getRunningBalanceByAccountPaginated(accountId, limit + 1, start).items
        val inRange = if (from == null) fetched else fetched.takeWhile { it.timestamp >= from }
        val rows = inRange.take(limit)
        val next = rows.lastOrNull()?.takeIf { inRange.size > limit }?.let { TransactionCursor(it.timestamp, it.transactionId.id) }
        return TransactionPage(rows, next)
    }
}

data class TransactionPage(
    val rows: List<AccountRow>,
    /** Where the next page starts, or null when this is the last one. */
    val next: TransactionCursor?,
)

/** A position in an account's newest-first transaction list. Its [encode]d form is opaque to callers. */
data class TransactionCursor(
    val timestamp: Instant,
    val id: Long,
) {
    fun encode(): String = "${timestamp.toEpochMilliseconds()}.$id"

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
