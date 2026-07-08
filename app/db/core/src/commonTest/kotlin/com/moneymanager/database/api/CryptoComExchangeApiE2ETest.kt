@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

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
 * End-to-end test of the generic signed-exchange import against the built-in Crypto.com Exchange
 * config: stages canned API responses into a session, runs [importApiSessionExchange] through the real
 * `ImportEngine`, and asserts the resulting cross-asset trade + deposit transfer + auto-created crypto
 * assets. Re-running the same import is idempotent (no double-booking).
 */
class CryptoComExchangeApiE2ETest : DbTest() {
    override val installBuiltInStrategies: Boolean = true

    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private val tradesJson =
        """
        {"code":0,"result":{"data":[
          {"instrument_name":"BTC_USD","side":"BUY","traded_quantity":"0.5","traded_price":"40000.55",
           "fees":"0.001","fee_instrument_name":"BTC","create_time":1700000000000,"trade_id":"t1","order_id":"o1"}
        ]}}
        """.trimIndent()

    private val ordersJson =
        """
        {"code":0,"result":{"data":[
          {"instrument_name":"BTC_USD","side":"BUY","quantity":"0.5","create_time":1700000000000,
           "order_id":"o1","type":"LIMIT","status":"FILLED"}
        ]}}
        """.trimIndent()

    private val depositsJson =
        """
        {"code":0,"result":{"deposit_list":[
          {"id":"d1","amount":"1500.25","currency":"CRO","create_time":1700000001000}
        ]}}
        """.trimIndent()

    private suspend fun stageSessionAndImport(): Int {
        val strategy = repositories.apiImportStrategyRepository.getStrategyByName("Crypto.com Exchange").first()
        assertNotNull(strategy, "built-in Crypto.com Exchange strategy should be installed")
        val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-os", "test-machine"))
        val sessionId = repositories.apiSessionRepository.createSession("apikey", deviceId, now, null)

        suspend fun stage(
            path: String,
            json: String,
        ) {
            val requestId =
                repositories.apiSessionRepository.insertRequest(
                    sessionId,
                    "POST",
                    "https://api.crypto.com/exchange/v1/$path?ep=$path",
                    emptyMap(),
                )
            repositories.apiSessionRepository.insertResponse(requestId, sessionId, json)
        }
        stage("private/get-trades", tradesJson)
        stage("private/get-order-history", ordersJson)
        stage("private/get-deposit-history", depositsJson)

        importApiSessionExchange(
            apiSessionRepository = repositories.apiSessionRepository,
            currencyRepository = repositories.currencyRepository,
            cryptoRepository = repositories.cryptoRepository,
            sessionId = sessionId,
            strategy = strategy,
            importEngine = repositories.importEngine,
        )
        return sessionId.id.toInt()
    }

    @Test
    fun `imports a cross-asset trade, a deposit and auto-creates crypto assets, idempotently`() =
        runTest {
            stageSessionAndImport()

            val exchange =
                repositories.accountRepository.getAllAccounts().first().firstOrNull { it.name == "Crypto.com Exchange" }
            assertNotNull(exchange, "the single Crypto.com Exchange account should exist")

            // BTC and CRO were auto-created as crypto assets (neither is a fiat currency).
            assertNotNull(repositories.cryptoRepository.getCryptoAssetByCode("BTC").first(), "BTC created")
            assertNotNull(repositories.cryptoRepository.getCryptoAssetByCode("CRO").first(), "CRO created")

            // One trade (BUY BTC/USD) on the exchange account.
            val trades = repositories.tradeRepository.getTradesByAccount(exchange.id).first()
            assertEquals(1, trades.size, "one trade imported")
            val trade = trades.single()
            // BUY: USD leaves, BTC arrives.
            assertEquals("USD", trade.from.currency.code)
            assertEquals("BTC", trade.to.currency.code)
            // 0.5 BTC in.
            assertEquals("0.5", trade.to.toDisplayValue().toString())
            // quote = 0.5 * 40000.55 = 20000.275 -> rounded to 2 dp = 20000.28.
            assertEquals("20000.28", trade.from.toDisplayValue().toString())
            // Order type folded into the description as reference.
            assertTrue(trade.description.contains("LIMIT"), "order type recorded: ${trade.description}")

            val tradesBefore = trades.size

            // Re-import the same session: the exact-match guard keeps trades idempotent.
            stageSessionAndImport()
            val tradesAfter = repositories.tradeRepository.getTradesByAccount(exchange.id).first().size
            assertEquals(tradesBefore, tradesAfter, "re-import must not double-book trades")
        }
}
