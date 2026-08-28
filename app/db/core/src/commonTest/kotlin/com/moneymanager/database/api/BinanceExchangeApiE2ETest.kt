@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.api

import com.moneymanager.apiimporter.importApiSessionExchange
import com.moneymanager.domain.model.AccountId
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

    // Simple Earn: principal moving between the Spot wallet and the Earn wallet. "PURCHASING" is still
    // in flight, so only the SUCCESS row imports.
    private val earnSubscriptionsJson =
        """
        {"rows":[
          {"purchaseId":26055,"asset":"XMR","amount":"5.81000000","time":1700000006000,"status":"SUCCESS"},
          {"purchaseId":26056,"asset":"XMR","amount":"1.00000000","time":1700000006100,"status":"PURCHASING"}
        ],"total":2}
        """.trimIndent()

    private val earnRedemptionsJson =
        """
        {"rows":[
          {"redeemId":40607,"asset":"BUSD","amount":"1373.16000000","time":1700000007000,"projectId":"BUSD001","status":"PAID"}
        ],"total":1}
        """.trimIndent()

    // Reward rows carry no id of any kind - only the composite key keeps them apart.
    private val earnRewardsBonusJson =
        """
        {"rows":[
          {"asset":"BUSD","rewards":"0.00006408","projectId":"BUSD001","type":"BONUS","time":1700000008000},
          {"asset":"BUSD","rewards":"0.00007000","projectId":"BUSD001","type":"BONUS","time":1700000008500}
        ],"total":2}
        """.trimIndent()

    // Same asset, project, second and amount as the first BONUS row, differing only by reward type - the
    // two must stay distinct records rather than one suppressing the other as a duplicate.
    private val earnRewardsRealtimeJson =
        """
        {"rows":[
          {"asset":"BUSD","rewards":"0.00006408","projectId":"BUSD001","type":"REALTIME","time":1700000008000}
        ],"total":1}
        """.trimIndent()

    // One dust conversion sweeping two small balances into BNB; the money is in the nested detail rows.
    private val dustJson =
        """
        {"total":1,"userAssetDribblets":[
          {"operateTime":1700000009000,"totalTransferedAmount":"0.00023200","totalServiceChargeAmount":"0.00000600",
           "transId":45178372831,
           "userAssetDribbletDetails":[
             {"transId":4359321,"fromAsset":"XRP","amount":"0.17015309","transferedAmount":"0.00009100",
              "serviceChargeAmount":"0.00000900","operateTime":1700000009000},
             {"transId":4359322,"fromAsset":"ADA","amount":"1.50000000","transferedAmount":"0.00014100",
              "serviceChargeAmount":"0.00000400","operateTime":1700000009000}
           ]}
        ]}
        """.trimIndent()

    private val dividendJson =
        """
        {"rows":[
          {"id":1637366104,"amount":"10.00000000","asset":"BHFT","divTime":1700000010000,"enInfo":"BHFT distribution",
           "tranId":2968885920}
        ],"total":1}
        """.trimIndent()

    // A universal transfer out to the Funding wallet; the PENDING row must not import.
    private val spotToFundingJson =
        """
        {"rows":[
          {"asset":"USDT","amount":"25.00000000","type":"MAIN_FUNDING","status":"CONFIRMED","tranId":11945860693,
           "timestamp":1700000011000},
          {"asset":"USDT","amount":"9.00000000","type":"MAIN_FUNDING","status":"PENDING","tranId":11945860694,
           "timestamp":1700000011500}
        ],"total":2}
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
        stage(
            "sapi/v1/simple-earn/flexible/history/subscriptionRecord" +
                "?ep=sapi/v1/simple-earn/flexible/history/subscriptionRecord",
            earnSubscriptionsJson,
        )
        stage(
            "sapi/v1/simple-earn/flexible/history/redemptionRecord?ep=sapi/v1/simple-earn/flexible/history/redemptionRecord",
            earnRedemptionsJson,
        )
        // The three rewardsRecord endpoints share one path and differ only by the required "type" param.
        stage(
            "sapi/v1/simple-earn/flexible/history/rewardsRecord?ep=sapi/v1/simple-earn/flexible/history/rewardsRecord?type=BONUS",
            earnRewardsBonusJson,
        )
        stage(
            "sapi/v1/simple-earn/flexible/history/rewardsRecord" +
                "?ep=sapi/v1/simple-earn/flexible/history/rewardsRecord?type=REALTIME",
            earnRewardsRealtimeJson,
        )
        stage("sapi/v1/asset/dribblet?ep=sapi/v1/asset/dribblet", dustJson)
        stage("sapi/v1/asset/assetDividend?ep=sapi/v1/asset/assetDividend", dividendJson)
        stage("sapi/v1/asset/transfer?ep=sapi/v1/asset/transfer?type=MAIN_FUNDING", spotToFundingJson)
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
            assertEquals(
                "Binance Bank",
                accountName(fiatDeposit.sourceAccountId),
                "the fiat endpoint's declared counterparty account is honoured, not the generic funding account",
            )
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
            // Picked by its own BNB amount - dust conversions also acquire BNB (see the Simple Earn test).
            val convert = trades.first { it.to.asset.code == "BNB" && it.to.toDisplayValue().toString() == "0.06154036" }
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

    private suspend fun accountName(id: AccountId?): String? {
        val wanted = id ?: return null
        return repositories.accountRepository
            .getAllAccounts()
            .first()
            .firstOrNull { it.id == wanted }
            ?.name
    }

    private suspend fun transfersInWindow() =
        repositories.transactionRepository
            .getTransactionsByDateRange(
                startDate = Instant.fromEpochMilliseconds(1_700_000_005_900L),
                endDate = Instant.fromEpochMilliseconds(1_700_000_012_000L),
            ).first()

    @Test
    fun `imports Simple Earn principal and rewards, dust conversions, distributions and wallet transfers`() =
        runTest {
            stageSessionAndImport()

            val exchange =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .firstOrNull { it.name == "Binance" }
            assertNotNull(exchange)
            val transfers = transfersInWindow()

            // Earn principal leaves the spot account for its own wallet account, and comes back on redemption.
            val subscription = transfers.single { it.amount.asset.code == "XMR" }
            assertEquals(exchange.id, subscription.sourceAccountId)
            assertEquals("Binance Earn", accountName(subscription.targetAccountId))
            assertEquals("5.81", subscription.amount.toDisplayValue().toString(), "the PURCHASING row must not import")

            val redemption = transfers.single { it.amount.asset.code == "BUSD" && it.amount.toDisplayValue().toString() == "1373.16" }
            assertEquals("Binance Earn", accountName(redemption.sourceAccountId))
            assertEquals(exchange.id, redemption.targetAccountId)

            // Rewards: two id-less rows kept apart by their composite key, credited from their own account.
            val rewards = transfers.filter { accountName(it.sourceAccountId) == "Binance Earn Rewards" }
            assertEquals(
                3,
                rewards.size,
                "every id-less reward row imports - including the REALTIME row identical to a BONUS one but " +
                    "for its type, which the composite id keeps distinct",
            )
            assertEquals(setOf("BUSD"), rewards.map { it.amount.asset.code }.toSet())
            assertEquals(
                listOf("0.00006408", "0.00006408", "0.00007"),
                rewards.map { it.amount.toDisplayValue().toString() }.sorted(),
            )

            // A distribution credits in from its own counterparty account.
            val dividend = transfers.single { it.amount.asset.code == "BHFT" }
            assertEquals(exchange.id, dividend.targetAccountId)
            assertEquals("Binance Distribution", accountName(dividend.sourceAccountId))

            // Spot -> Funding wallet; the PENDING row is filtered out.
            val walletTransfer = transfers.single { it.amount.asset.code == "USDT" && it.sourceAccountId == exchange.id }
            assertEquals("Binance Funding Wallet", accountName(walletTransfer.targetAccountId))
            assertEquals("25", walletTransfer.amount.toDisplayValue().toString())

            // Dust: the nested detail rows are the movement, each acquiring BNB with the swept asset.
            val trades = repositories.tradeRepository.getTradesByAccount(exchange.id).first()
            val dustXrp = trades.single { it.from.asset.code == "XRP" }
            assertEquals("BNB", dustXrp.to.asset.code)
            assertEquals("0.000091", dustXrp.to.toDisplayValue().toString())
            assertEquals("0.17015309", dustXrp.from.toDisplayValue().toString())
            assertTrue(trades.any { it.from.asset.code == "ADA" && it.to.asset.code == "BNB" }, "both detail rows import")

            val tradesBefore = trades.size
            val transfersBefore = transfers.size
            stageSessionAndImport()
            val tradesAfter =
                repositories.tradeRepository
                    .getTradesByAccount(exchange.id)
                    .first()
                    .size
            assertEquals(tradesBefore, tradesAfter)
            assertEquals(transfersBefore, transfersInWindow().size, "re-import must not double-book the new endpoints")
        }
}
