package com.moneymanager.ui.coinbase

import com.moneymanager.apiimporter.downloadApiSessionExchange
import com.moneymanager.apiimporter.importApiSessionExchange
import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.importengineapi.AccountMatchKey
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.ImportAccountIntent
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.LocalAccountKey
import com.moneymanager.importengineapi.createApiCredential
import com.moneymanager.importengineapi.createApiSession
import com.moneymanager.rest.ApiRequestSigner
import com.moneymanager.rest.ApiSessionTrafficRecorder
import com.moneymanager.rest.createApiClient
import com.moneymanager.test.database.DbTest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * End-to-end download + import against the built-in Coinbase config, over a mocked Coinbase API.
 * Exercises the generic engine capabilities Coinbase needs:
 * - ES256 JWT authentication
 * - a token-cursor paged value endpoint (`v2/accounts`) whose lowercase wallet ids fan out into the
 *   transactions endpoint's path
 * - token-cursor paging on each wallet's ledger
 * - trade grouping of buy/convert legs by `buy.id`/`trade.id` (including a card purchase that has only
 *   the crypto leg), and of Advanced Trade fills by order, charging the commission both legs repeat once
 */
class CoinbaseExchangeApiE2ETest : DbTest() {
    override val installBuiltInStrategies: Boolean = true

    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    private val apiKey = "organizations/org-1/apiKeys/key-1"

    private val btcWallet = "5f1f2d7a-btc-wallet"
    private val gbpWallet = "9c0e44b1-gbp-wallet"

    private fun accountsPage(startingAfter: String?): String =
        when (startingAfter) {
            null ->
                """{"pagination":{"next_starting_after":"$btcWallet"},
                   "data":[{"id":"$btcWallet","currency":{"code":"BTC"},"type":"wallet"}]}"""
            btcWallet ->
                """{"pagination":{"next_starting_after":null},
                   "data":[{"id":"$gbpWallet","currency":{"code":"GBP"},"type":"fiat"}]}"""
            else -> error("unexpected accounts cursor $startingAfter")
        }

    private fun btcLedgerPage(startingAfter: String?): String =
        when (startingAfter) {
            null ->
                """{"pagination":{"next_starting_after":"page-2"},"data":[
                  {"id":"r1","type":"receive","status":"completed","created_at":"2024-01-01T10:00:00Z",
                   "amount":{"amount":"0.5","currency":"BTC"},"native_amount":{"amount":"15000","currency":"GBP"},
                   "network":{"hash":"onchain-r1","network_name":"bitcoin"}},
                  {"id":"c1-btc","type":"trade","status":"completed","created_at":"2024-01-02T10:00:00Z",
                   "amount":{"amount":"-0.1","currency":"BTC"},"native_amount":{"amount":"-3000","currency":"GBP"},
                   "trade":{"id":"T1"}},
                  {"id":"b1","type":"buy","status":"completed","created_at":"2024-01-03T10:00:00Z",
                   "amount":{"amount":"0.2","currency":"BTC"},"native_amount":{"amount":"5000","currency":"GBP"},
                   "buy":{"id":"B1"}},
                  {"id":"fa-btc","type":"advanced_trade_fill","status":"completed","created_at":"2024-01-04T10:00:00Z",
                   "amount":{"amount":"0.003","currency":"BTC"},"native_amount":{"amount":"30","currency":"GBP"},
                   "advanced_trade_fill":{"order_id":"O1","product_id":"BTC-GBP","commission":"0.18","fill_price":"10000"}},
                  {"id":"fb-btc","type":"advanced_trade_fill","status":"completed","created_at":"2024-01-04T10:00:01Z",
                   "amount":{"amount":"0.002","currency":"BTC"},"native_amount":{"amount":"20","currency":"GBP"},
                   "advanced_trade_fill":{"order_id":"O1","product_id":"BTC-GBP","commission":"0.12","fill_price":"10000"}}
                ]}"""
            "page-2" ->
                """{"pagination":{"next_starting_after":null},"data":[
                  {"id":"s1","type":"send","status":"completed","created_at":"2024-01-05T10:00:00Z",
                   "amount":{"amount":"-0.05","currency":"BTC"},"native_amount":{"amount":"-1500","currency":"GBP"},
                   "to":{"resource":"bitcoin_address","address":"bc1qdestination"},
                   "network":{"hash":"onchain-s1","network_name":"bitcoin"}},
                  {"id":"s2","type":"send","status":"pending","created_at":"2024-01-06T10:00:00Z",
                   "amount":{"amount":"-0.3","currency":"BTC"},"native_amount":{"amount":"-9000","currency":"GBP"}}
                ]}"""
            else -> error("unexpected BTC ledger cursor $startingAfter")
        }

    private val gbpLedger =
        """{"pagination":{"next_starting_after":null},"data":[
          {"id":"d1","type":"fiat_deposit","status":"completed","created_at":"2024-01-01T09:00:00Z",
           "amount":{"amount":"1000","currency":"GBP"},"native_amount":{"amount":"1000","currency":"GBP"}},
          {"id":"c1-gbp","type":"trade","status":"completed","created_at":"2024-01-02T10:00:00Z",
           "amount":{"amount":"3000","currency":"GBP"},"native_amount":{"amount":"3000","currency":"GBP"},
           "trade":{"id":"T1"}},
          {"id":"fa-gbp","type":"advanced_trade_fill","status":"completed","created_at":"2024-01-04T10:00:00Z",
           "amount":{"amount":"-30","currency":"GBP"},"native_amount":{"amount":"-30","currency":"GBP"},
           "advanced_trade_fill":{"order_id":"O1","product_id":"BTC-GBP","commission":"0.18","fill_price":"10000"}},
          {"id":"fb-gbp","type":"advanced_trade_fill","status":"completed","created_at":"2024-01-04T10:00:01Z",
           "amount":{"amount":"-20","currency":"GBP"},"native_amount":{"amount":"-20","currency":"GBP"},
           "advanced_trade_fill":{"order_id":"O1","product_id":"BTC-GBP","commission":"0.12","fill_price":"10000"}}
        ]}"""

    @Test
    fun `downloads every wallet ledger page then imports its movements and trades`() =
        runTest {
            val strategy = coinbaseStrategy()
            val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-machine", "Test OS"))
            val credentialId = repositories.importEngine.createApiCredential(apiKey, now)
            val sessionId = repositories.importEngine.createApiSession(apiKey, deviceId, now, credentialId)
            val unauthenticated = mutableListOf<String>()
            download(strategy, sessionId, emptyMap(), unauthenticated = unauthenticated)
            assertEquals(emptyList(), unauthenticated, "every request must carry a signed JWT")

            suspend fun import() =
                importApiSessionExchange(
                    apiSessionRepository = repositories.apiSessionRepository,
                    accountRepository = repositories.accountRepository,
                    currencyRepository = repositories.currencyRepository,
                    cryptoRepository = repositories.cryptoRepository,
                    sessionId = sessionId,
                    strategy = strategy,
                    importEngine = repositories.importEngine,
                )
            import()

            val coinbase =
                assertNotNull(
                    repositories.accountRepository
                        .getAllAccounts()
                        .first()
                        .firstOrNull { it.name == "Coinbase" },
                )
            val trades = repositories.tradeRepository.getTradesByAccount(coinbase.id).first()
            // The convert (T1, two legs), the card purchase (B1, one leg) and the Advanced Trade order (O1,
            // two fills of two legs each, booked as one trade at the amounts the ledger settled).
            assertEquals(3, trades.size, "trades: $trades")

            // BTC: +0.5 receive, -0.1 convert, +0.2 card buy, -0.05 send, +0.005 Advanced Trade order.
            // GBP: +1000 deposit, +3000 convert, -5000 card buy funded by +5000 from the card,
            //      -50 Advanced Trade order, -0.3 commission (repeated on both legs of each fill, charged once).
            assertEquals(mapOf("BTC" to "0.555", "GBP" to "3949.7"), balances(coinbase.name))

            val cardFunding =
                assertNotNull(
                    repositories.accountRepository
                        .getAllAccounts()
                        .first()
                        .firstOrNull { it.name == "Coinbase Payment Methods" },
                    "a card-funded purchase books its payment against the configured funding account",
                )
            assertEquals(mapOf("GBP" to "-5000"), balances(cardFunding.name))

            import()
            assertEquals(
                3,
                repositories.tradeRepository
                    .getTradesByAccount(coinbase.id)
                    .first()
                    .size,
                "re-import must not double-book",
            )
            assertEquals(mapOf("BTC" to "0.555", "GBP" to "3949.7"), balances(coinbase.name))

            val requests = repositories.apiSessionRepository.getRequestsBySession(sessionId)
            assertTrue(requests.any { it.url.contains("fv=$btcWallet") }, "wallet ids keep their case")
        }

    /**
     * A bank export that already recorded a Coinbase deposit names its real far end (the bank account),
     * while the Coinbase ledger can only say "fiat deposit". The ledger's row must reconcile against the
     * bank's, not credit the Coinbase account a second time.
     */
    @Test
    fun `a fiat deposit a bank export already recorded is not counted twice`() =
        runTest {
            val gbp = assertNotNull(repositories.currencyRepository.getCurrencyByCode("GBP").first())
            val coinbaseKey = LocalAccountKey("coinbase")
            val bankKey = LocalAccountKey("bank")
            val depositTime = Instant.parse("2024-01-01T09:00:04Z")
            repositories.importEngine.import(
                ImportBatch(
                    accountsToCreate =
                        listOf(coinbaseKey to "Coinbase", bankKey to "Bank").map { (key, name) ->
                            ImportAccountIntent(
                                key = key,
                                match = AccountMatchKey.ByName(name),
                                name = name,
                                openingDate = depositTime,
                                source = Source.Manual,
                            )
                        },
                    transfers =
                        listOf(
                            ImportTransfer(
                                source = Source.Manual,
                                fromAccount = AccountRef.Local(bankKey),
                                toAccount = AccountRef.Local(coinbaseKey),
                                timestamp = depositTime,
                                description = "Faster payment to Coinbase",
                                amount = Money.fromDisplayValue(BigDecimal("1000"), gbp),
                            ),
                        ),
                ),
            )

            val strategy = coinbaseStrategy()
            val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-machine", "Test OS"))
            val credentialId = repositories.importEngine.createApiCredential(apiKey, now)
            val sessionId = repositories.importEngine.createApiSession(apiKey, deviceId, now, credentialId)
            download(strategy, sessionId, emptyMap())
            importApiSessionExchange(
                apiSessionRepository = repositories.apiSessionRepository,
                accountRepository = repositories.accountRepository,
                currencyRepository = repositories.currencyRepository,
                cryptoRepository = repositories.cryptoRepository,
                sessionId = sessionId,
                strategy = strategy,
                importEngine = repositories.importEngine,
            )

            // Same balances as without the bank record: its £1000 stands in for the ledger's deposit.
            assertEquals(mapOf("BTC" to "0.555", "GBP" to "3949.7"), balances("Coinbase"))
            assertEquals(mapOf("GBP" to "-1000"), balances("Bank"))
        }

    private suspend fun coinbaseStrategy() =
        repositories.apiImportStrategyRepository
            .getAllStrategies()
            .first()
            .single { it.name == "Coinbase" }

    /** Downloads [sessionId] against the mocked API, noting each request as `path?cursor`. */
    private suspend fun download(
        strategy: ApiImportStrategy,
        sessionId: ApiSessionId,
        watermarks: Map<String, Instant>,
        requested: MutableList<String> = mutableListOf(),
        unauthenticated: MutableList<String> = mutableListOf(),
        gbpLedgerPage: (String?) -> String = { gbpLedger },
    ) {
        val apiClient =
            createApiClient(
                trafficRecorder = ApiSessionTrafficRecorder(sessionId = sessionId, importEngine = repositories.importEngine),
                engine =
                    MockEngine { request ->
                        val path = request.url.encodedPath
                        if (request.headers[HttpHeaders.Authorization]?.startsWith("Bearer ey") != true) unauthenticated += path
                        val params = request.url.parameters
                        requested += "$path?${params["starting_after"] ?: params["cursor"] ?: ""}"
                        val body =
                            when (path) {
                                "/v2/accounts" -> accountsPage(params["starting_after"])
                                "/v2/accounts/$btcWallet/transactions" -> btcLedgerPage(params["starting_after"])
                                "/v2/accounts/$gbpWallet/transactions" -> gbpLedgerPage(params["starting_after"])
                                else -> error("unexpected request $path")
                            }
                        respond(
                            content = body,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
            )
        downloadApiSessionExchange(
            apiClient = apiClient,
            signer = ApiRequestSigner(checkNotNull(strategy.config.requestSigning)),
            apiKey = apiKey,
            apiSecret = PRIVATE_KEY,
            apiSessionRepository = repositories.apiSessionRepository,
            sessionId = sessionId,
            strategy = strategy,
            importEngine = repositories.importEngine,
            watermarks = watermarks,
            rateLimitMillis = 0,
        )
    }

    @Test
    fun `a repeat download stops walking a wallet ledger once it reaches the previous download`() =
        runTest {
            val strategy = coinbaseStrategy()
            val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-machine", "Test OS"))
            val credentialId = repositories.importEngine.createApiCredential(apiKey, now)

            suspend fun downloadOnce(): List<String> {
                val sessionId = repositories.importEngine.createApiSession(apiKey, deviceId, now, credentialId)
                val watermarks = repositories.apiSessionRepository.getDownloadWatermarks(credentialId, sessionId)
                return mutableListOf<String>().also { download(strategy, sessionId, watermarks, requested = it) }
            }
            val secondPage = "/v2/accounts/$btcWallet/transactions?page-2"

            assertTrue(secondPage in downloadOnce(), "a first download walks the whole ledger")
            val repeat = downloadOnce()
            assertTrue(secondPage !in repeat, "the ledger's first page is already older than the watermark: $repeat")
            assertTrue("/v2/accounts/$gbpWallet/transactions?" in repeat, "every wallet is still checked for new rows")
        }

    @Test
    fun `a wallet ledger that echoes back the cursor it was sent stops paging`() =
        runTest {
            val strategy = coinbaseStrategy()
            val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-machine", "Test OS"))
            val credentialId = repositories.importEngine.createApiCredential(apiKey, now)
            val sessionId = repositories.importEngine.createApiSession(apiKey, deviceId, now, credentialId)
            val echoing = gbpLedger.replace(""""next_starting_after":null""", """"next_starting_after":"stuck"""")
            val requested = mutableListOf<String>()

            download(strategy, sessionId, emptyMap(), requested = requested, gbpLedgerPage = { echoing })

            assertEquals(
                listOf("/v2/accounts/$gbpWallet/transactions?", "/v2/accounts/$gbpWallet/transactions?stuck"),
                requested.filter { it.startsWith("/v2/accounts/$gbpWallet/") },
            )
        }

    private suspend fun balances(accountName: String): Map<String, String> {
        repositories.maintenanceService.refreshMaterializedViews()
        val account =
            repositories.accountRepository
                .getAllAccounts()
                .first()
                .first { it.name == accountName }
        return repositories.transactionRepository
            .getAccountBalances()
            .first()
            .filter { it.accountId == account.id }
            .associate { it.balance.asset.code to it.balance.toDisplayValue().toString() }
    }

    private companion object {
        // A throwaway P-256 key generated with `openssl ecparam -name prime256v1 -genkey -noout`.
        val PRIVATE_KEY =
            """
            -----BEGIN EC PRIVATE KEY-----
            MHcCAQEEILwbfi+kL3sQAqaJsLC6NIfvmm3wUIXh0TJDgx38TUVOoAoGCCqGSM49
            AwEHoUQDQgAE8dbx6ad/1STzz/k4jjguAKzdUM32LSUkt1xyccKeF3l2w/qDID0u
            Xfe95zGewLuMD1RdTOXzQCVauBwc8zk/xg==
            -----END EC PRIVATE KEY-----
            """.trimIndent()
    }
}
