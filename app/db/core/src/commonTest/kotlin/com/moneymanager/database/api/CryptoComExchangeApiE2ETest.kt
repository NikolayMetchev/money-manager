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
import kotlin.test.assertFailsWith
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
          {"instrument_name":"BTC_USD","side":"BUY","traded_quantity":"0.5","traded_price":"40000.54",
           "fees":"0.001","fee_instrument_name":"BTC","create_time":1700000000000,"trade_id":"t1","order_id":"o1"},
          {"instrument_name":"BTC_USD","side":"SELL","traded_quantity":"0.1","traded_price":"41000.00",
           "fees":"-0.52","fee_instrument_name":"USD","create_time":1700000000100,"trade_id":"t2","order_id":"o2"}
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
          {"id":"d1","amount":"1500.25","currency":"CRO","create_time":1700000001000,
           "source_address":"cro1senderwallet","txid":"0xabc123","network_id":"CRONOS"}
        ]}}
        """.trimIndent()

    private suspend fun stageSessionAndImport(
        deposits: String = depositsJson,
        trades: String = tradesJson,
    ): Int {
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
        stage("private/get-trades", trades)
        stage("private/get-order-history", ordersJson)
        stage("private/get-deposit-history", deposits)

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

            // Two trades (BUY + SELL BTC/USD) on the exchange account.
            val trades = repositories.tradeRepository.getTradesByAccount(exchange.id).first()
            assertEquals(2, trades.size, "both trades imported")
            val trade = trades.first { it.to.asset.code == "BTC" }
            // BUY: USD leaves, BTC arrives.
            assertEquals("USD", trade.from.asset.code)
            assertEquals("BTC", trade.to.asset.code)
            // 0.5 BTC in.
            assertEquals("0.5", trade.to.toDisplayValue().toString())
            // quote = 0.5 * 40000.54 = 20000.27, exactly representable at USD's 2-decimal scale.
            assertEquals("20000.27", trade.from.toDisplayValue().toString())
            // Order type folded into the description as reference.
            assertTrue(trade.description.contains("LIMIT"), "order type recorded: ${trade.description}")

            // The SELL's fiat fee books as its own movement to the Fees account.
            val feesAccount =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .first { it.name == "Crypto.com Exchange Fees" }
            val feeTransfer =
                repositories.transactionRepository
                    .getTransactionsByDateRange(
                        startDate = Instant.fromEpochMilliseconds(1_700_000_000_050L),
                        endDate = Instant.fromEpochMilliseconds(1_700_000_000_150L),
                    ).first()
                    .first { it.targetAccountId == feesAccount.id }
            assertEquals("USD", feeTransfer.amount.asset.code)
            assertEquals("0.52", feeTransfer.amount.toDisplayValue().toString())

            // Provenance points at the real JSON node so the audit view can expand to it.
            val deposit =
                repositories.transactionRepository
                    .getTransactionsByDateRange(
                        startDate = Instant.fromEpochMilliseconds(1_700_000_000_500L),
                        endDate = Instant.fromEpochMilliseconds(1_700_000_001_500L),
                    ).first()
                    .first { it.targetAccountId == exchange.id && it.amount.asset.code == "CRO" }
            val depositSource =
                repositories.transferSourceRepository
                    .getSourcesForTransaction(deposit.id)
                    .first()
                    .source
            assertEquals(
                "\$.result.deposit_list[0]",
                (depositSource as com.moneymanager.domain.model.Source.Api).jsonPath?.value,
            )

            // The deposit's counterparty is a per-wallet account keyed by the sender address (not the
            // generic funding account), so the same wallet reconciles across sources.
            val wallet =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .first { it.id == deposit.sourceAccountId }
            assertEquals("CRONOS:cro1senderwallet", wallet.name, "deposit should come from the per-wallet account")
            // The on-chain txid is stored as a unique identifier for cross-source reconciliation.
            val depositAttrs = repositories.transferAttributeRepository.getByTransaction(deposit.id).first()
            assertTrue(
                depositAttrs.any { it.attributeType.name == "blockchain-txid" && it.value == "0xabc123" },
                "txid stored as unique id: $depositAttrs",
            )

            val tradesBefore = trades.size

            suspend fun depositCount() =
                repositories.transactionRepository
                    .getTransactionsByDateRange(
                        startDate = Instant.fromEpochMilliseconds(1_700_000_000_500L),
                        endDate = Instant.fromEpochMilliseconds(1_700_000_001_500L),
                    ).first()
                    .count { it.targetAccountId == exchange.id }
            val depositsBefore = depositCount()

            // Re-import the same session: the exact-match guard keeps trades idempotent, and the provider
            // id (ApiMultiKey) keeps deposits/withdrawals idempotent.
            stageSessionAndImport()
            assertEquals(
                tradesBefore,
                repositories.tradeRepository
                    .getTradesByAccount(exchange.id)
                    .first()
                    .size,
                "re-import must not double-book trades",
            )
            assertEquals(depositsBefore, depositCount(), "re-import must not double-book deposits")
        }

    @Test
    fun `a value with more precision than the asset's scale fails the import instead of rounding`() =
        runTest {
            // The fiat fee "-0.525570" USD (6 decimals against USD's scale of 100) is not exactly
            // representable, so the import must throw rather than silently round.
            val excessPrecisionTrades =
                """
                {"code":0,"result":{"data":[
                  {"instrument_name":"BTC_USD","side":"SELL","traded_quantity":"0.1","traded_price":"41000.00",
                   "fees":"-0.525570","fee_instrument_name":"USD","create_time":1700000000100,"trade_id":"t9","order_id":"o9"}
                ]}}
                """.trimIndent()
            val failure =
                assertFailsWith<IllegalArgumentException> {
                    stageSessionAndImport(trades = excessPrecisionTrades)
                }
            assertTrue("t9" in failure.message.orEmpty(), "failure names the trade: ${failure.message}")
            assertTrue("USD" in failure.message.orEmpty(), "failure names the asset: ${failure.message}")
        }

    @Test
    fun `an internal deposit aliases to the App account and reconciles with the CSV leg regardless of order`() =
        runTest {
            // The CSV export's view of the same App -> Exchange transfer, already imported:
            // Crypto.com -> Crypto.com Exchange, 1500.25 CRO, 30s before the API's create_time.
            val croId = repositories.importEngine.createCrypto("CRO")
            val cro = repositories.cryptoRepository.getCryptoAssetById(croId).first()!!
            val app = repositories.importEngine.createAppAccount("Crypto.com")
            val exchange = repositories.importEngine.createAppAccount("Crypto.com Exchange")
            repositories.transactionRepository.createTransfers(
                transfers =
                    listOf(
                        Transfer(
                            id = TransferId(0),
                            timestamp = Instant.fromEpochMilliseconds(1_700_000_000_970L),
                            description = "Transfer: App wallet -> Exchange",
                            sourceAccountId = app,
                            targetAccountId = exchange,
                            amount = Money.fromDisplayValue("1500.25", cro),
                        ),
                    ),
                sources = listOf(Source.SampleGenerator),
            )

            // The API's view: an INTERNAL_DEPOSIT (address = "INTERNAL_DEPOSIT") aliases the counterparty
            // to the "Crypto.com" App account, so it is booked as the identical Crypto.com -> Crypto.com
            // Exchange transfer and reconciles against the CSV leg above.
            val internalDeposit =
                """
                {"code":0,"result":{"deposit_list":[
                  {"id":"d9","amount":"1500.25","currency":"CRO","create_time":1700000001000,
                   "address":"INTERNAL_DEPOSIT","source_address":"","txid":"internal-1"}
                ]}}
                """.trimIndent()
            stageSessionAndImport(deposits = internalDeposit)

            val apiDeposit =
                repositories.transactionRepository
                    .getTransactionsByDateRange(
                        Instant.fromEpochMilliseconds(1_700_000_000_980L),
                        Instant.fromEpochMilliseconds(1_700_000_001_020L),
                    ).first()
                    .first { it.amount.asset.code == "CRO" }
            // Booked directly App -> Exchange (not via a funding/wallet account).
            assertEquals(app, apiDeposit.sourceAccountId, "internal deposit source should be the App account")
            assertEquals(exchange, apiDeposit.targetAccountId, "internal deposit target should be the Exchange account")
            // Reconciled to the CSV leg: excluded from balances + linked.
            val attrs = repositories.transferAttributeRepository.getByTransaction(apiDeposit.id).first()
            assertTrue(attrs.any { it.attributeType.id.id == -1L }, "the API leg should be excluded (reconciled)")
            assertTrue(
                repositories.transferRelationshipRepository
                    .getByTransfer(apiDeposit.id)
                    .first()
                    .isNotEmpty(),
                "the API leg should be linked to the CSV leg",
            )
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
                exchangeTransfers.firstOrNull { it.targetAccountId == exchange.id && it.amount.asset.code == "CRO" }
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
