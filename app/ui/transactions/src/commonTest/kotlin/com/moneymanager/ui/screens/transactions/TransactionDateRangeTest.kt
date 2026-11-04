package com.moneymanager.ui.screens.transactions

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AccountRow
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.PagingInfo
import com.moneymanager.domain.model.PagingResult
import com.moneymanager.domain.model.TransferId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Instant

class TransactionDateRangeTest {
    private val usd = Currency(id = CurrencyId(1L), code = "USD", name = "US Dollar", scaleFactor = 100)

    private fun row(
        id: Long,
        epochSeconds: Long,
    ) = AccountRow(
        transactionId = TransferId(id),
        timestamp = Instant.fromEpochSeconds(epochSeconds),
        description = "t$id",
        accountId = AccountId(1L),
        transactionAmount = Money(100, usd),
        runningBalance = Money(100, usd),
        sourceAccountId = AccountId(1L),
        targetAccountId = AccountId(2L),
    )

    private fun page(
        items: List<AccountRow>,
        hasMore: Boolean,
    ) = PagingResult(items, PagingInfo(items.lastOrNull()?.timestamp, items.lastOrNull()?.transactionId, hasMore))

    @Test
    fun `no range is inactive and changes nothing`() {
        val range = TransactionDateRange()
        val loaded = page(listOf(row(3, 30), row(2, 20)), hasMore = true)
        assertFalse(range.isActive)
        assertNull(range.firstPageCursor())
        assertEquals(loaded, range.clipOlder(loaded))
        assertEquals(loaded.items, range.clipNewer(loaded.items))
    }

    @Test
    fun `the first page starts just after the to bound so it is inclusive`() {
        val to = Instant.fromEpochSeconds(100)
        val cursor = TransactionDateRange(to = to).firstPageCursor()
        assertEquals(to + 1.nanoseconds, cursor?.lastTimestamp)
        assertEquals(TransferId(0), cursor?.lastId)
        assertTrue(cursor!!.hasMore)
    }

    @Test
    fun `rows older than from are cut and end the paging`() {
        val range = TransactionDateRange(from = Instant.fromEpochSeconds(20))
        val clipped = range.clipOlder(page(listOf(row(3, 30), row(2, 20), row(1, 10)), hasMore = true))
        assertEquals(listOf(row(3, 30), row(2, 20)), clipped.items)
        assertFalse(clipped.pagingInfo.hasMore)
    }

    @Test
    fun `a page wholly inside from keeps its paging`() {
        val range = TransactionDateRange(from = Instant.fromEpochSeconds(5))
        val loaded = page(listOf(row(3, 30), row(2, 20)), hasMore = true)
        assertEquals(loaded, range.clipOlder(loaded))
    }

    @Test
    fun `rows newer than to are dropped when paging towards newer rows`() {
        val range = TransactionDateRange(to = Instant.fromEpochSeconds(20))
        val loaded = listOf(row(4, 40), row(3, 30), row(2, 20))
        val clipped = range.clipNewer(loaded)
        assertEquals(listOf(row(2, 20)), clipped)
        assertFalse(range.hasNewer(loaded, clipped, repositoryHasMore = true))
    }
}
