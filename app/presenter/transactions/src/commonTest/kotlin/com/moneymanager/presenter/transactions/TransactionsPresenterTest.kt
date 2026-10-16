package com.moneymanager.presenter.transactions

import androidx.compose.runtime.collectAsState
import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AccountRow
import com.moneymanager.domain.model.AttributeType
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.CurrencyScaleFactors
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.PageWithTargetIndex
import com.moneymanager.domain.model.PagingInfo
import com.moneymanager.domain.model.PagingResult
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferAttribute
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.TransactionReadRepository
import com.moneymanager.query.TransactionDetailQuery
import com.moneymanager.query.TransactionQuery
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class TransactionsPresenterTest {
    private val gbp =
        Currency(id = CurrencyId(1), code = "GBP", name = "Pound Sterling", scaleFactor = CurrencyScaleFactors.DEFAULT_SCALE_FACTOR)
    private val current = AccountId(1)
    private val savings = AccountId(2)
    private val start = Instant.parse("2024-01-01T00:00:00Z")

    // Account 1: ids 1..60 an hour apart (newest first); account 2: ids 101..105.
    private val rowsByAccount =
        mapOf(
            current to (60 downTo 1).map { row(current, it.toLong()) },
            savings to (105 downTo 101).map { row(savings, it.toLong()) },
        )

    private val repository =
        mock<TransactionReadRepository> {
            everySuspend { getRunningBalanceByAccountPaginated(any(), any(), any(), any()) } calls
                { (account: AccountId, size: Int, paging: PagingInfo?) -> older(account, size, paging) }
            everySuspend { getRunningBalanceByAccountPaginatedBackward(any(), any(), any(), any(), any()) } calls
                { args -> newer(args.arg(0), args.arg(1), args.arg(2), args.arg<TransactionId>(3).id) }
            everySuspend { getPageContainingTransaction(any(), any(), any(), any()) } calls
                { (account: AccountId, target: TransferId, size: Int) -> around(account, target, size) }
            every { getTransactionById(any()) } calls { (id: Long) -> flowOf(transfer(id)) }
            everySuspend { getTransactionsByIds(any()) } returns emptyMap()
        }

    private val details =
        TransactionDetailQuery(
            repository,
            mock { },
            mock { every { getByTransfer(any()) } returns flowOf(emptyList()) },
            mock { everySuspend { getSourcesForTransaction(any()) } returns emptyList() },
        )

    @Test
    fun theFirstPageIsTheNewestRows() =
        runTest {
            present().test {
                val model = awaitUntil { it.loaded }
                assertEquals((60L downTo 56).toList(), model.ids())
                assertTrue(model.hasOlder)
                assertFalse(model.hasNewer)
            }
        }

    @Test
    fun loadingOlderExtendsTheWindowAndDropsTheNewestPastTheCap() =
        runTest {
            present().test {
                var model = awaitUntil { it.loaded }
                repeat(MAX_PAGES) {
                    val before = model.rows.last()
                    model.eventSink(TransactionsEvent.LoadOlder)
                    model = awaitUntil { !it.extending && it.rows.last() != before }
                }
                assertEquals(MAX_PAGES * PAGE, model.rows.size)
                assertEquals(55L, model.ids().first())
                assertTrue(model.hasNewer)

                model.eventSink(TransactionsEvent.LoadNewer)
                model = awaitUntil { !it.extending && it.ids().first() == 60L }
                assertFalse(model.hasNewer)
                assertEquals(MAX_PAGES * PAGE, model.rows.size)
                assertTrue(model.hasOlder)
            }
        }

    @Test
    fun aRangeBoundsBothEnds() =
        runTest {
            present().test {
                awaitUntil { it.loaded }.eventSink(TransactionsEvent.SetRange(TransactionRange(from = at(10), to = at(13))))
                val model = awaitUntil { it.loaded && it.range.from != null }
                assertEquals(listOf(12L, 11, 10), model.ids())
                assertFalse(model.hasOlder)
                assertFalse(model.hasNewer)
            }
        }

    @Test
    fun aJumpLandsOnTheDateAndCanPageBackToNewerRows() =
        runTest {
            present().test {
                awaitUntil { it.loaded }.eventSink(TransactionsEvent.JumpTo(at(15)))
                var model = awaitUntil { it.loaded && it.focusId != null }
                assertEquals(TransferId(14), model.focusId)
                assertEquals((14L downTo 10).toList(), model.ids())
                assertTrue(model.hasNewer)

                model.eventSink(TransactionsEvent.LoadNewer)
                model = awaitUntil { it.ids().first() == 19L }
                assertEquals((19L downTo 10).toList(), model.ids())
            }
        }

    @Test
    fun showTransferMovesToItsAccountAndFocusesIt() =
        runTest {
            present().test {
                awaitUntil { it.loaded }.eventSink(TransactionsEvent.ShowTransfer(savings, TransferId(103)))
                val model = awaitUntil { it.loaded && it.accountId == savings }
                assertEquals(TransferId(103), model.focusId)
                assertTrue(TransferId(103) in model.rows.map { it.transactionId })
            }
        }

    @Test
    fun anotherAccountStartsAgainFromItsNewestRow() =
        runTest {
            val account = MutableStateFlow(current)
            moleculeFlow(RecompositionMode.Immediate) {
                presentTransactions(TransactionQuery(repository), details, account.collectAsState().value, PAGE)
            }.test {
                awaitUntil { it.loaded }.eventSink(TransactionsEvent.JumpTo(at(15)))
                awaitUntil { it.focusId != null }
                account.value = savings
                val model = awaitUntil { it.loaded && it.accountId == savings }
                assertEquals((105L downTo 101).toList(), model.ids())
            }
        }

    @Test
    fun selectingARowLoadsItsDetail() =
        runTest {
            present().test {
                awaitUntil { it.loaded }.eventSink(TransactionsEvent.Select(TransferId(58)))
                val detail = awaitUntil { it.detail != null }.detail!!
                assertEquals(TransferId(58), detail.row.transactionId)
                assertEquals("ref-58", detail.attributes.single().value)
            }
        }

    private fun present() =
        moleculeFlow(RecompositionMode.Immediate) {
            presentTransactions(TransactionQuery(repository), details, current, PAGE)
        }

    private suspend fun ReceiveTurbine<TransactionsModel>.awaitUntil(predicate: (TransactionsModel) -> Boolean): TransactionsModel {
        while (true) {
            val model = awaitItem()
            if (predicate(model)) return model
        }
    }

    private fun TransactionsModel.ids() = rows.map { it.transactionId.id }

    private fun at(hour: Int) = start + hour.hours

    private fun rowsOf(account: AccountId) = rowsByAccount.getValue(account)

    private fun older(
        account: AccountId,
        size: Int,
        paging: PagingInfo?,
    ): PagingResult<AccountRow> {
        val after =
            rowsOf(account).filter { row ->
                val last = paging?.lastTimestamp ?: return@filter true
                row.timestamp < last || (row.timestamp == last && row.transactionId.id < paging.lastId!!.id)
            }
        return PagingResult(after.take(size), PagingInfo(null, null, hasMore = after.size > size))
    }

    private fun newer(
        account: AccountId,
        size: Int,
        timestamp: Instant,
        id: Long,
    ): PagingResult<AccountRow> {
        val before = rowsOf(account).filter { it.timestamp > timestamp || (it.timestamp == timestamp && it.transactionId.id > id) }
        return PagingResult(before.takeLast(size), PagingInfo(null, null, hasMore = before.size > size))
    }

    private fun around(
        account: AccountId,
        target: TransferId,
        size: Int,
    ): PageWithTargetIndex<AccountRow> {
        val rows = rowsOf(account)
        val offset = (rows.indexOfFirst { it.transactionId == target } - size / 2).coerceIn(0, (rows.size - size).coerceAtLeast(0))
        val items = rows.drop(offset).take(size)
        return PageWithTargetIndex(items, PagingInfo(null, null, hasMore = offset + items.size < rows.size), hasPrevious = offset > 0)
    }

    private fun row(
        account: AccountId,
        id: Long,
    ) = AccountRow(
        transactionId = TransferId(id),
        timestamp = at((id % 100).toInt()),
        description = "row $id",
        accountId = account,
        transactionAmount = Money.fromDisplayValue("1", gbp),
        runningBalance = Money.fromDisplayValue("$id", gbp),
        sourceAccountId = AccountId(99),
        targetAccountId = account,
    )

    private fun transfer(id: Long) =
        Transfer(
            id = TransferId(id),
            timestamp = at((id % 100).toInt()),
            description = "row $id",
            sourceAccountId = AccountId(99),
            targetAccountId = current,
            amount = Money.fromDisplayValue("1", gbp),
            attributes = listOf(TransferAttribute(1, TransferId(id), AttributeType(AttributeTypeId(1), "reference"), "ref-$id")),
        )

    private companion object {
        const val PAGE = 5
    }
}
