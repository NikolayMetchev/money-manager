package com.moneymanager.query

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AccountRow
import com.moneymanager.domain.model.PageWithTargetIndex
import com.moneymanager.domain.model.PagingInfo
import com.moneymanager.domain.model.PagingResult
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.TransferId
import dev.mokkery.answering.calls
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant

class TransactionQueryTest {
    private val accountId = AccountId(1)

    // Newest first, one a day: id 5 on the 5th down to id 1 on the 1st.
    private val rows = (5 downTo 1).map { row(it.toLong(), "2024-03-0${it}T12:00:00Z") }

    private val query =
        TransactionQuery(
            mock {
                everySuspend { getRunningBalanceByAccountPaginated(any(), any(), any(), any()) } calls
                    { (_: AccountId, size: Int, paging: PagingInfo?) -> pageOf(size, paging) }
                everySuspend { getRunningBalanceByAccountPaginatedBackward(any(), any(), any(), any(), any()) } calls
                    { args -> newerPageOf(args.arg(1), args.arg(2), args.arg<TransactionId>(3).id) }
                everySuspend { getPageContainingTransaction(any(), any(), any(), any()) } calls
                    { (_: AccountId, target: TransferId, size: Int) -> pageAround(target, size) }
            },
        )

    /** Answers like the real query: rows strictly before the paging position, newest first. */
    private fun pageOf(
        size: Int,
        paging: PagingInfo?,
    ): PagingResult<AccountRow> {
        val after =
            rows.filter { row ->
                val lastTimestamp = paging?.lastTimestamp ?: return@filter true
                row.timestamp < lastTimestamp || (row.timestamp == lastTimestamp && row.transactionId.id < paging.lastId!!.id)
            }
        return PagingResult(after.take(size), PagingInfo(null, null, hasMore = after.size > size))
    }

    /** Answers like the real backward query: the [size] rows nearest after the position, newest first. */
    private fun newerPageOf(
        size: Int,
        timestamp: Instant,
        id: Long,
    ): PagingResult<AccountRow> {
        val newer = rows.filter { it.timestamp > timestamp || (it.timestamp == timestamp && it.transactionId.id > id) }
        return PagingResult(newer.takeLast(size), PagingInfo(null, null, hasMore = newer.size > size))
    }

    /** Answers like the real query: [size] rows with [target] in the middle where possible. */
    private fun pageAround(
        target: TransferId,
        size: Int,
    ): PageWithTargetIndex<AccountRow> {
        val position = rows.indexOfFirst { it.transactionId == target }
        val offset = (position - size / 2).coerceIn(0, (rows.size - size).coerceAtLeast(0))
        val items = rows.drop(offset).take(size)
        return PageWithTargetIndex(items, PagingInfo(null, null, hasMore = offset + items.size < rows.size), hasPrevious = offset > 0)
    }

    @Test
    fun pagesFollowTheCursorToTheEnd() =
        runTest {
            val first = query.page(accountId, limit = 2)
            val second = query.page(accountId, limit = 2, cursor = first.next)
            val last = query.page(accountId, limit = 2, cursor = second.next)

            assertEquals(listOf(5L, 4, 3, 2, 1), (first.rows + second.rows + last.rows).map { it.transactionId.id })
            assertNull(last.next)
        }

    @Test
    fun toPlacesTheFirstPageAndFromEndsTheLast() =
        runTest {
            val from = Instant.parse("2024-03-02T00:00:00Z")
            val page = query.page(accountId, limit = 2, from = from, to = Instant.parse("2024-03-05T12:00:00Z"))
            val rest = query.page(accountId, limit = 2, from = from, cursor = page.next)

            assertEquals(listOf(4L, 3), page.rows.map { it.transactionId.id })
            assertEquals(listOf(2L), rest.rows.map { it.transactionId.id })
            assertNull(rest.next)
        }

    @Test
    fun aPageEndingAtFromOffersNoEmptyNextPage() =
        runTest {
            val page = query.page(accountId, limit = 2, from = Instant.parse("2024-03-04T00:00:00Z"))

            assertEquals(listOf(5L, 4), page.rows.map { it.transactionId.id })
            assertNull(page.next)
        }

    @Test
    fun theNewestPageHasNoPreviousAndAnOlderOneDoes() =
        runTest {
            val first = query.page(accountId, limit = 2)
            val second = query.page(accountId, limit = 2, cursor = first.next)

            assertNull(first.previous)
            assertEquals(second.rows.first().cursor(), second.previous)
        }

    @Test
    fun pageBeforeWalksBackToTheNewestRow() =
        runTest {
            val oldest = query.page(accountId, limit = 2, cursor = TransactionCursor(Instant.parse("2024-03-02T12:00:00Z"), 0))
            val newer = query.pageBefore(accountId, limit = 2, cursor = oldest.previous!!)
            val newest = query.pageBefore(accountId, limit = 2, cursor = newer.previous!!)

            assertEquals(listOf(1L), oldest.rows.map { it.transactionId.id })
            assertEquals(listOf(3L, 2), newer.rows.map { it.transactionId.id })
            assertEquals(listOf(5L, 4), newest.rows.map { it.transactionId.id })
            assertNull(newest.previous)
            assertEquals(newest.rows.last().cursor(), newest.next)
        }

    @Test
    fun pageBeforeStopsAtTo() =
        runTest {
            val to = Instant.parse("2024-03-04T00:00:00Z")
            val page = query.page(accountId, limit = 1, to = Instant.parse("2024-03-02T00:00:00Z"))
            val newer = query.pageBefore(accountId, limit = 3, cursor = TransactionCursor(page.rows.single().timestamp, 1), to = to)

            assertEquals(listOf(3L, 2), newer.rows.map { it.transactionId.id })
            assertNull(newer.previous)
        }

    @Test
    fun aPageStartingAtToOnlyOffersNewerRowsInsideIt() =
        runTest {
            val bounded = query.page(accountId, limit = 2, to = Instant.parse("2024-03-04T00:00:00Z"))
            val jumped =
                query.page(
                    accountId,
                    limit = 2,
                    to = Instant.parse("2024-03-05T00:00:00Z"),
                    cursor = TransactionCursor(Instant.parse("2024-03-04T00:00:00Z"), 0),
                )

            assertEquals(listOf(3L, 2), bounded.rows.map { it.transactionId.id })
            assertNull(bounded.previous)
            assertEquals(listOf(3L, 2), jumped.rows.map { it.transactionId.id })
            assertEquals(jumped.rows.first().cursor(), jumped.previous)
        }

    @Test
    fun pageContainingCentresTheTransferAndOffersBothWays() =
        runTest {
            val page = query.pageContaining(accountId, TransferId(3), limit = 3)

            assertEquals(listOf(4L, 3, 2), page.rows.map { it.transactionId.id })
            assertEquals(page.rows.first().cursor(), page.previous)
            assertEquals(page.rows.last().cursor(), page.next)
        }

    @Test
    fun cursorsRoundTripAndRejectAnythingElse() {
        val cursor = TransactionCursor(Instant.parse("2024-03-05T12:00:00Z"), 5)

        assertEquals(cursor, TransactionCursor.decode(cursor.encode()))
        for (bad in listOf("", "abc", "1.2.3", "1.", ".1")) {
            assertFailsWith<IllegalArgumentException>(bad) { TransactionCursor.decode(bad) }
        }
    }

    private fun row(
        id: Long,
        at: String,
    ) = AccountRow(
        transactionId = TransferId(id),
        timestamp = Instant.parse(at),
        description = "row $id",
        accountId = accountId,
        transactionAmount = money("1", gbp),
        runningBalance = money("$id", gbp),
        sourceAccountId = AccountId(2),
        targetAccountId = accountId,
    )
}
