@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.api

import com.moneymanager.apiimporter.importApiSessionExchange
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.importengineapi.createAccount
import com.moneymanager.importengineapi.createCrypto
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
    fun `imports a cross-asset trade, a deposit and auto-creates crypto assets, idempotently`() =
        runTest {
            stageSessionAndImport()

            val exchange =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .firstOrNull { it.name == "Crypto.com Exchange" }
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

            // Provenance points at the real JSON node so the audit view can expand to it.
            val deposit =
                repositories.transactionRepository
                    .getTransactionsByDateRange(
                        startDate = Instant.fromEpochMilliseconds(1_700_000_000_500L),
                        endDate = Instant.fromEpochMilliseconds(1_700_000_001_500L),
                    ).first()
                    .first { it.targetAccountId == exchange.id && it.amount.currency.code == "CRO" }
            val depositSource =
                repositories.transferSourceRepository
                    .getSourcesForTransaction(deposit.id)
                    .first()
                    .source
            assertEquals(
                "\$.result.deposit_list[0]",
                (depositSource as com.moneymanager.domain.model.Source.Api).jsonPath?.value,
            )

            val tradesBefore = trades.size

            // Re-import the same session: the exact-match guard keeps trades idempotent.
            stageSessionAndImport()
            val tradesAfter =
                repositories.tradeRepository
                    .getTradesByAccount(exchange.id)
                    .first()
                    .size
            assertEquals(tradesBefore, tradesAfter, "re-import must not double-book trades")
        }

    @Test
    fun `reconciles an App to Exchange transfer into one internal transfer`() =
        runTest {
            // Seed the App side: the CSV "Crypto.com" account records the same CRO moving out (to a
            // dangling external wallet) that the Exchange deposit below records moving in.
            val croId = repositories.importEngine.createCrypto("CRO")
            val cro = repositories.cryptoRepository.getCryptoAssetById(croId).first()!!
            val appAccount = repositories.importEngine.createAppAccount("Crypto.com")
            val external = repositories.importEngine.createAppAccount("App wallet -> Exchange")
            repositories.transactionRepository.createTransfers(
                transfers =
                    listOf(
                        Transfer(
                            id = TransferId(0),
                            timestamp = Instant.fromEpochMilliseconds(1_700_000_001_000L),
                            description = "Transfer: App wallet -> Exchange",
                            sourceAccountId = appAccount,
                            targetAccountId = external,
                            amount = Money.fromDisplayValue("1500.25", cro),
                        ),
                    ),
                sources = listOf(Source.SampleGenerator),
            )

            stageSessionAndImport()

            val accounts = repositories.accountRepository.getAllAccounts().first()
            val exchange = accounts.first { it.name == "Crypto.com Exchange" }
            // The Exchange's incoming CRO deposit was rewritten to come from the App "Crypto.com" account
            // (one internal transfer), instead of the generic funding account.
            val exchangeTransfers =
                repositories.transactionRepository
                    .getTransactionsByDateRange(
                        startDate = Instant.fromEpochMilliseconds(1_700_000_000_000L),
                        endDate = Instant.fromEpochMilliseconds(1_700_000_002_000L),
                    ).first()
            val croDeposit =
                exchangeTransfers.firstOrNull { it.targetAccountId == exchange.id && it.amount.currency.code == "CRO" }
            assertNotNull(croDeposit, "the CRO deposit should target the Exchange account")
            assertEquals(appAccount, croDeposit.sourceAccountId, "deposit rewritten to come from the App account")

            // The stale App-side leg is now excluded from balances (reconciled).
            val appLeg = exchangeTransfers.firstOrNull { it.sourceAccountId == appAccount && it.targetAccountId == external }
            assertNotNull(appLeg, "the original App leg still exists")
            assertTrue(
                appLeg.attributes.any { it.attributeType.id.id == -1L },
                "the App leg should carry the excluded attribute",
            )
        }
}

/** Creates a plain app account (name only) for test seeding, via the engine's create-account helper. */
private suspend fun com.moneymanager.importengineapi.ImportEngine.createAppAccount(name: String) =
    createAccount(
        Account(
            id =
                com.moneymanager.domain.model
                    .AccountId(0),
            name = name,
            openingDate = Instant.fromEpochMilliseconds(1_600_000_000_000L),
        ),
        Source.SampleGenerator,
    )
