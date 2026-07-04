@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.repository

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.createAccount
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The transaction-matrix currency cell drill-down: pagination must filter by currency in SQL, not
 * client-side over loaded pages — otherwise a currency whose transactions are older than the first
 * page(s) shows a balance with an apparently empty transaction list.
 */
class RunningBalancePaginationCurrencyFilterTest : DbTest() {
    private val baseTime = Instant.fromEpochMilliseconds(1_700_000_000_000)

    private suspend fun currency(code: String): Currency =
        repositories.currencyRepository
            .getAllCurrencies()
            .first()
            .first { it.code == code }

    private suspend fun createAccount(name: String): AccountId {
        repositories.accountRepository.createAccount(Account(id = AccountId(0), name = name, openingDate = baseTime))
        return repositories.accountRepository
            .getAllAccounts()
            .first()
            .first { it.name == name }
            .id
    }

    /** Imports one transfer of [amount] at [minutesAfterBase] minutes past the base time. */
    private suspend fun importTransfer(
        sourceId: AccountId,
        targetId: AccountId,
        amount: Money,
        minutesAfterBase: Int,
        description: String,
    ): TransferId =
        repositories.transactionRepository
            .importTransfers(
                transfers =
                    listOf(
                        Transfer(
                            id = TransferId(-1),
                            timestamp = Instant.fromEpochMilliseconds(baseTime.toEpochMilliseconds() + minutesAfterBase * 60_000L),
                            description = description,
                            sourceAccountId = sourceId,
                            targetAccountId = targetId,
                            amount = amount,
                        ),
                    ),
                newAttributes = emptyMap(),
                sources = listOf(Source.SampleGenerator),
                updates = emptyList(),
                updateSources = emptyList(),
            ).single()

    @Test
    fun `currency-filtered pagination returns only that currency's rows even when they are older than a page`() =
        runTest {
            val gbp = currency("GBP")
            val usd = currency("USD")
            val card = createAccount("Card")
            val shop = createAccount("Shop")

            // Two OLD USD transactions, then a full page's worth of newer GBP ones.
            importTransfer(card, shop, Money(1000, usd), minutesAfterBase = 0, description = "Old USD 1")
            importTransfer(card, shop, Money(2000, usd), minutesAfterBase = 1, description = "Old USD 2")
            repeat(5) { i ->
                importTransfer(card, shop, Money(100L * (i + 1), gbp), minutesAfterBase = 10 + i, description = "GBP $i")
            }
            repositories.maintenanceService.fullRefreshMaterializedViews()

            // Unfiltered first page (newest first) holds only GBP rows — the old-USD-invisible setup.
            val unfiltered =
                repositories.transactionRepository
                    .getRunningBalanceByAccountPaginated(card, pageSize = 5, pagingInfo = null)
            assertEquals(5, unfiltered.items.size)
            assertTrue(unfiltered.items.all { it.transactionAmount.currency.code == "GBP" })

            // Filtered by USD: both old rows arrive on the FIRST page.
            val usdPage =
                repositories.transactionRepository
                    .getRunningBalanceByAccountPaginated(card, pageSize = 5, pagingInfo = null, currencyId = usd.id)
            assertEquals(listOf("Old USD 2", "Old USD 1"), usdPage.items.map { it.description })
            assertTrue(usdPage.items.all { it.transactionAmount.currency.code == "USD" })
            assertEquals(false, usdPage.pagingInfo.hasMore)

            // Running balances accumulate within the filtered currency only (card is the source side).
            assertEquals(listOf(-3000L, -1000L), usdPage.items.map { it.runningBalance.amount })

            // Centering on a USD transaction with the filter active positions within USD rows only.
            val usdTransferId = usdPage.items.last().transactionId
            val page =
                repositories.transactionRepository
                    .getPageContainingTransaction(card, TransferId(usdTransferId.id), pageSize = 5, currencyId = usd.id)
            assertEquals(2, page.items.size)
            assertTrue(page.targetIndex >= 0)
            assertTrue(page.items.all { it.transactionAmount.currency.code == "USD" })
        }
}
