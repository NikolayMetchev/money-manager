package com.moneymanager.query

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AccountRow
import com.moneymanager.domain.model.PagingInfo
import com.moneymanager.domain.model.PagingResult
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.TransactionReadRepository
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
            mock<TransactionReadRepository> {
                everySuspend { getRunningBalanceByAccountPaginated(any(), any(), any(), any()) } calls
                    { (_: AccountId, size: Int, paging: PagingInfo?) -> pageOf(size, paging) }
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
