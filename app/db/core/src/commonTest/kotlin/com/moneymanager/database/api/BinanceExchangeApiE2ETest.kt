@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.api

import com.moneymanager.apiimporter.importApiSessionExchange
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * End-to-end test of the generic signed-exchange import against the built-in Binance config. Binance
 * exercises engine capabilities Kraken/Crypto.com don't: [PredicateOp][com.moneymanager.domain.model.apistrategy.PredicateOp]
 * item filters (status-gated deposits/withdrawals), a fixed trade side per endpoint (Convert, fiat
 * buy/sell), a custom [pattern timestamp][com.moneymanager.domain.model.apistrategy.TimestampFormat.PATTERN]
 * (withdrawal `applyTime`), two data endpoints sharing one path disambiguated only by a static query
 * param (`fiat/orders` deposit vs withdrawal), and a symbol-scoped trade id disambiguated by
 * `compositeIdFields` across a fan-out endpoint's several symbols.
 *
 * The fan-out download loop itself (candidate symbol resolution, per-symbol requests) is exercised by
 * `BinanceFanOutDownloadE2ETest` in `app:ui:core`; this test stages responses directly and exercises the
 * import (parse) half, matching [KrakenExchangeApiE2ETest]'s pattern.
 */
class BinanceExchangeApiE2ETest : DbTest() {
    override val installBuiltInStrategies: Boolean = true

    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    // One credited (status 1) and one still-pending (status 0) deposit - only the credited one imports.
    private val depositsJson =
        """
        [
          {"id":"d1","coin":"BTC","amount":"0.01000000","status":1,"address":"bc1qdepositaddr",
           "network":"BTC","txId":"onchain-dep-1","insertTime":1700000001000},
          {"id":"d2","coin":"BTC","amount":"5.00000000","status":0,"address":"bc1qpending",
           "network":"BTC","txId":"onchain-dep-2","insertTime":1700000001500}
        ]
        """.trimIndent()

    // Completed (status 6) withdrawal with a separate fee, "applyTime" in Binance's own date-time
    // pattern (not epoch millis) - exercises TimestampFormat.PATTERN.
    private val withdrawalsJson =
        """
        [
          {"id":"w1","coin":"USDT","amount":"100.00000000","transactionFee":"1.00000000","status":6,
           "address":"0xwithdrawaddr","network":"ETH","txId":"onchain-wd-1",
           "applyTime":"2023-11-14 22:13:20"}
        ]
        """.trimIndent()

    private val fiatDepositsJson =
        """
        {"code":"000000","message":"success","data":[
          {"orderNo":"fd1","fiatCurrency":"GBP","amount":"500.00","status":"Successful","createTime":1700000002000}
        ],"total":1,"success":true}
        """.trimIndent()

    private val fiatWithdrawalsJson =
        """
        {"code":"000000","message":"success","data":[
          {"orderNo":"fw1","fiatCurrency":"GBP","amount":"50.00","status":"Successful","createTime":1700000002500}
        ],"total":1,"success":true}
        """.trimIndent()

    // Buying BTC with GBP: obtainAmount = BTC received, sourceAmount = GBP spent.
    private val fiatBuyJson =
        """
        {"code":"000000","message":"success","data":[
          {"orderNo":"fb1","sourceAmount":"200.0","fiatCurrency":"GBP","obtainAmount":"0.005",
           "cryptoCurrency":"BTC","totalFee":"2.0","status":"Completed","createTime":1700000003000}
        ],"total":1,"success":true}
        """.trimIndent()

    private val convertJson =
        """
        {"list":[
          {"quoteId":"q1","orderId":942, "orderStatus":"SUCCESS","fromAsset":"USDT","fromAmount":"20",
           "toAsset":"BNB","toAmount":"0.06154036","createTime":1700000004000}
        ],"startTime":1699900000000,"endTime":1700000004000,"limit":100,"moreData":false}
        """.trimIndent()

    // Two different symbols share the raw numeric "id" (28457) - a real Binance shape (myTrades ids are
    // scoped per symbol) that compositeIdFields (symbol + id) must keep from colliding.
    private val myTradesBtcUsdtJson =
        """
        [{"symbol":"BTCUSDT","id":28457,"orderId":1000,"price":"40000.00","qty":"0.10000000",
          "quoteQty":"4000.00","commission":"4.00000000","commissionAsset":"USDT","time":1700000005000,
          "isBuyer":true}]
        """.trimIndent()
    private val myTradesEthUsdtJson =
        """
        [{"symbol":"ETHUSDT","id":28457,"orderId":2000,"price":"2000.00","qty":"1.00000000",
          "quoteQty":"2000.00","commission":"2.00000000","commissionAsset":"USDT","time":1700000005500,
          "isBuyer":false}]
        """.trimIndent()

    private suspend fun stageSessionAndImport(): Int {
        val strategy = repositories.apiImportStrategyRepository.getStrategyByName("Binance").first()
        assertNotNull(strategy, "built-in Binance strategy should be installed")
        val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-os", "test-machine"))
        val sessionId = repositories.apiSessionRepository.createSession("apikey", deviceId, now, null)

        suspend fun stage(
            marker: String,
            json: String,
        ) {
            val requestId =
                repositories.apiSessionRepository.insertRequest(
                    sessionId,
                    "GET",
                    "https://api.binance.com/$marker",
                    emptyMap(),
                )
            repositories.apiSessionRepository.insertResponse(requestId, sessionId, json)
        }
        stage("sapi/v1/capital/deposit/hisrec?ep=sapi/v1/capital/deposit/hisrec", depositsJson)
        stage("sapi/v1/capital/withdraw/history?ep=sapi/v1/capital/withdraw/history", withdrawalsJson)
        // The two fiat/orders endpoints share a path - the marker's static-param suffix ("transactionType=0"
        // vs "=1", baked in by endpointDedupeKey) is what tells them apart on import, exactly like Kraken's
        // two Ledgers endpoints.
        stage("sapi/v1/fiat/orders?ep=sapi/v1/fiat/orders?transactionType=0", fiatDepositsJson)
        stage("sapi/v1/fiat/orders?ep=sapi/v1/fiat/orders?transactionType=1", fiatWithdrawalsJson)
        stage("sapi/v1/fiat/payments?ep=sapi/v1/fiat/payments?transactionType=0", fiatBuyJson)
        stage("sapi/v1/convert/tradeFlow?ep=sapi/v1/convert/tradeFlow", convertJson)
        // Two fan-out requests for the SAME data endpoint (different symbols) both carry the base "ep="
        // marker - the import phase matches by that marker alone, not by fan-out value, so both are parsed.
        stage("api/v3/myTrades?ep=api/v3/myTrades&fv=BTCUSDT", myTradesBtcUsdtJson)
        stage("api/v3/myTrades?ep=api/v3/myTrades&fv=ETHUSDT", myTradesEthUsdtJson)

        importApiSessionExchange(
            apiSessionRepository = repositories.apiSessionRepository,
            accountRepository = repositories.accountRepository,
            currencyRepository = repositories.currencyRepository,
            cryptoRepository = repositories.cryptoRepository,
            sessionId = sessionId,
            strategy = strategy,
            importEngine = repositories.importEngine,
        )
        return sessionId.id.toInt()
    }

    @Test
    fun `imports status-filtered transfers, both fiat_orders endpoints, fixed-side trades and fan-out spot trades, idempotently`() =
        runTest {
            stageSessionAndImport()

            val exchange =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .firstOrNull { it.name == "Binance" }
            assertNotNull(exchange, "the single Binance account should exist")

            // Only the status==1 deposit imported; the pending (status 0) one was filtered out.
            val btcDeposits =
                repositories.transactionRepository
                    .getTransactionsByDateRange(
                        startDate = Instant.fromEpochMilliseconds(1_700_000_000_500L),
                        endDate = Instant.fromEpochMilliseconds(1_700_000_001_800L),
                    ).first()
                    .filter { it.targetAccountId == exchange.id && it.amount.asset.code == "BTC" }
            assertEquals(1, btcDeposits.size, "the still-pending deposit must not be imported")
            assertEquals(
                "0.01",
                btcDeposits
                    .single()
                    .amount
                    .toDisplayValue()
                    .toString(),
            )

            // Withdrawal parsed from a "yyyy-MM-dd HH:mm:ss" applyTime (TimestampFormat.PATTERN), fee
            // booked as its own linked transfer.
            val usdtWithdrawal =
                repositories.transactionRepository
                    .getTransactionsByDateRange(
                        startDate = Instant.fromEpochMilliseconds(1_700_000_000_000L),
                        endDate = Instant.fromEpochMilliseconds(1_700_000_010_000L),
                    ).first()
                    .first {
                        it.sourceAccountId == exchange.id &&
                            it.amount.asset.code == "USDT" &&
                            it.amount.toDisplayValue().toString() == "100"
                    }
            val withdrawalFee =
                repositories.transactionRepository
                    .getTransactionsByDateRange(
                        startDate = Instant.fromEpochMilliseconds(1_700_000_000_000L),
                        endDate = Instant.fromEpochMilliseconds(1_700_000_010_000L),
                    ).first()
                    .firstOrNull {
                        it.sourceAccountId == exchange.id &&
                            it.amount.asset.code == "USDT" &&
                            it.amount.toDisplayValue().toString() == "1"
                    }
            assertNotNull(withdrawalFee, "the withdrawal fee should be booked as its own linked transfer")
            assertNotNull(usdtWithdrawal)

            // Both fiat/orders endpoints (transactionType=0 deposit, =1 withdrawal) told apart correctly.
            val fiatDeposit =
                repositories.transactionRepository
                    .getTransactionsByDateRange(
                        startDate = Instant.fromEpochMilliseconds(1_700_000_001_800L),
                        endDate = Instant.fromEpochMilliseconds(1_700_000_002_200L),
                    ).first()
                    .firstOrNull { it.targetAccountId == exchange.id && it.amount.asset.code == "GBP" }
            assertNotNull(fiatDeposit, "fiat deposit (transactionType=0) should be booked as incoming")
            val fiatWithdrawal =
                repositories.transactionRepository
                    .getTransactionsByDateRange(
                        startDate = Instant.fromEpochMilliseconds(1_700_000_002_200L),
                        endDate = Instant.fromEpochMilliseconds(1_700_000_002_800L),
                    ).first()
                    .firstOrNull { it.sourceAccountId == exchange.id && it.amount.asset.code == "GBP" }
            assertNotNull(fiatWithdrawal, "fiat withdrawal (transactionType=1) should be booked as outgoing")

            // Fixed-side trades: fiat buy (BTC acquired with GBP) and Convert (BNB acquired with USDT).
            val trades = repositories.tradeRepository.getTradesByAccount(exchange.id).first()
            val fiatBuy = trades.first { it.to.asset.code == "BTC" && it.from.asset.code == "GBP" }
            assertEquals("0.005", fiatBuy.to.toDisplayValue().toString())
            val convert = trades.first { it.to.asset.code == "BNB" }
            assertEquals("USDT", convert.from.asset.code)

            // Fan-out spot trades: BTCUSDT and ETHUSDT both carry the raw id "28457", but
            // compositeIdFields (symbol + id) kept them from colliding into one trade.
            assertTrue(trades.any { it.to.asset.code == "BTC" && it.from.asset.code == "USDT" }, "BTCUSDT fan-out trade imported")
            assertTrue(trades.any { it.to.asset.code == "USDT" && it.from.asset.code == "ETH" }, "ETHUSDT fan-out trade imported")

            val tradesBefore = trades.size

            // Re-import the same session: idempotent.
            stageSessionAndImport()
            assertEquals(
                tradesBefore,
                repositories.tradeRepository
                    .getTradesByAccount(exchange.id)
                    .first()
                    .size,
                "re-import must not double-book trades",
            )
        }
}
