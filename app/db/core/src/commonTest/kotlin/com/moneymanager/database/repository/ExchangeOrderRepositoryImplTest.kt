@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.repository

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.repository.OrderUpsertResult
import com.moneymanager.importengineapi.createCrypto
import com.moneymanager.importengineapi.createTrade
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.createAccount
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class ExchangeOrderRepositoryImplTest : DbTest() {
    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private suspend fun account(name: String = "Exchange"): AccountId =
        repositories.accountRepository.createAccount(Account(id = AccountId(0), name = name, openingDate = now))

    private suspend fun upsert(
        accountId: AccountId,
        orderRef: String = "o1",
        status: String? = "ACTIVE",
        avgPrice: String? = null,
    ): OrderUpsertResult =
        repositories.exchangeOrderRepository.upsertOrder(
            accountId = accountId,
            orderRef = orderRef,
            clientOid = "co-1",
            side = "BUY",
            orderType = "LIMIT",
            timeInForce = "GOOD_TILL_CANCEL",
            status = status,
            limitPrice = "100.00",
            quantity = "5",
            avgPrice = avgPrice,
            createdAt = now,
            updatedAt = null,
            source = Source.Manual,
        )

    @Test
    // Test names in this package are dexed for the Android device test run, whose SimpleName grammar
    // allows spaces but not commas — keep punctuation out of the backticked names here.
    fun `upsert creates then dedupes an identical order and revises a changed one`() =
        runTest {
            val accountId = account()

            val created = upsert(accountId)
            assertEquals(OrderUpsertResult.Outcome.CREATED, created.outcome)

            val unchanged = upsert(accountId)
            assertEquals(OrderUpsertResult.Outcome.UNCHANGED, unchanged.outcome)
            assertEquals(created.id, unchanged.id)

            val updated = upsert(accountId, status = "FILLED", avgPrice = "99.98")
            assertEquals(OrderUpsertResult.Outcome.UPDATED, updated.outcome)
            assertEquals(created.id, updated.id)

            val order = repositories.exchangeOrderRepository.getOrderById(created.id).first()!!
            assertEquals("FILLED", order.status)
            assertEquals("99.98", order.avgPrice)
            assertEquals(2L, order.revisionId, "content change bumps the revision")
            assertEquals(
                1,
                repositories.exchangeOrderRepository
                    .getOrdersByAccount(accountId)
                    .first()
                    .size,
                "upsert never duplicates",
            )
        }

    @Test
    fun `the same order ref on different accounts is two distinct orders`() =
        runTest {
            val a = account("Exchange A")
            val b = account("Exchange B")
            val first = upsert(a)
            val second = upsert(b)
            assertEquals(OrderUpsertResult.Outcome.CREATED, second.outcome)
            assertTrue(first.id != second.id)
        }

    @Test
    fun `linkTrade is idempotent and drives both fill queries`() =
        runTest {
            val accountId = account()
            val gbp =
                repositories.currencyRepository
                    .getAllCurrencies()
                    .first()
                    .first { it.code == "GBP" }
            val btcId = repositories.importEngine.createCrypto("BTC")
            val btc = repositories.cryptoRepository.getCryptoAssetById(btcId).first()!!
            val tradeId =
                repositories.importEngine.createTrade(
                    timestamp = now,
                    description = "Buy BTC/GBP",
                    fromAccountId = accountId,
                    fromAmount = Money.fromDisplayValue("100.00", gbp),
                    toAccountId = accountId,
                    toAmount = Money.fromDisplayValue("0.001", btc),
                )
            val order = upsert(accountId)

            repositories.exchangeOrderRepository.linkTrade(order.id, tradeId)
            repositories.exchangeOrderRepository.linkTrade(order.id, tradeId)

            val fills = repositories.exchangeOrderRepository.getFillTradesForOrder(order.id).first()
            assertEquals(listOf(tradeId), fills.map { it.id }, "re-linking must not duplicate")
            assertEquals(
                mapOf(order.id to 1L),
                repositories.exchangeOrderRepository.getFillCountsByAccount(accountId).first(),
            )
        }

    @Test
    fun `deleting the account cascades to its orders and links`() =
        runTest {
            val accountId = account()
            val order = upsert(accountId)
            assertEquals(1L, repositories.exchangeOrderRepository.countOrdersByAccount(accountId).first())

            repositories.accountRepository.deleteAccount(accountId)

            assertEquals(0L, repositories.exchangeOrderRepository.countOrdersByAccount(accountId).first())
            assertEquals(null, repositories.exchangeOrderRepository.getOrderById(order.id).first())
        }
}
