package com.moneymanager.ui.screens.transactions

import com.moneymanager.domain.model.AccountRow
import com.moneymanager.domain.model.PagingInfo
import com.moneymanager.domain.model.PagingResult
import com.moneymanager.domain.model.TransferId
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Instant

/**
 * The date range of an account's transactions, both ends inclusive and each optional.
 *
 * It is applied on top of the repository's `(timestamp, id)` keyset paging rather than in SQL, so the
 * paginated statements keep using the `(account_id, timestamp)` index.
 */
@Serializable
internal data class TransactionDateRange(
    val from: Instant? = null,
    val to: Instant? = null,
) {
    val isActive: Boolean get() = from != null || to != null

    /** Where the first page starts: the query keeps rows before the cursor, so start just after [to]. */
    fun firstPageCursor(): PagingInfo? = to?.let { PagingInfo(it + 1.nanoseconds, TransferId(0), hasMore = true) }

    /** Cuts a page that runs past [from]; nothing older can be in the range, so there is no next page. */
    fun clipOlder(page: PagingResult<AccountRow>): PagingResult<AccountRow> {
        if (from == null) return page
        val inRange = page.items.takeWhile { it.timestamp >= from }
        return if (inRange.size == page.items.size) {
            page
        } else {
            PagingResult(inRange, page.pagingInfo.copy(hasMore = false))
        }
    }

    /** Drops rows newer than [to] from a page loaded towards newer rows. */
    fun clipNewer(items: List<AccountRow>): List<AccountRow> = if (to == null) items else items.dropWhile { it.timestamp > to }

    /** Whether older-to-newer paging can continue: the page was complete and the repository has more. */
    fun hasNewer(
        loaded: List<AccountRow>,
        clipped: List<AccountRow>,
        repositoryHasMore: Boolean,
    ): Boolean = clipped.size == loaded.size && repositoryHasMore
}
