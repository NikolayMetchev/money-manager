package com.moneymanager.ui.bybit

import com.moneymanager.apiimporter.downloadApiSessionExchange
import com.moneymanager.apiimporter.importApiSessionExchange
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
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
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * End-to-end download + import against the built-in Bybit config, over a mocked Bybit V5 API.
 * Exercises the generic engine capabilities Bybit needs:
 * - HMAC signing with every value in a header, plus a static recv-window header
 * - date windows paged by an already-URL-encoded `nextPageCursor`, sent back without double-encoding
 * - a non-windowed page-number walk (Convert)
 * - direction read from a row field (Earn's mixed Stake/Redeem list)
 * - signed ledger amounts (derivatives PnL), and an endpoint failing with a non-zero `retCode`
 */
class BybitExchangeApiE2ETest : DbTest() {
    override val installBuiltInStrategies: Boolean = true

    // The engine anchors its date windows to the real clock, so the rows must be recent in real time.
    private val t0 = Instant.fromEpochMilliseconds((Clock.System.now() - 10.days).toEpochMilliseconds())

    private fun ms(offset: Duration) = (t0 + offset).toEpochMilliseconds()

    /** Rows per endpoint (keyed by path + category), each paired with the epoch-ms its window filter uses. */
    private val rows: Map<String, List<Pair<Long, String>>> =
        mapOf(
            "/v5/execution/list?spot" to
                listOf(
                    ms(1.days) to
                        """{"symbol":"BTCUSDT","side":"Buy","execType":"Trade","execQty":"0.02","execValue":"800",
                           "execFee":"0.00002","feeCurrency":"BTC","execTime":"${ms(1.days)}","execId":"e1","orderId":"o1"}""",
                    ms(2.days) to
                        """{"symbol":"BTCUSDT","side":"Sell","execType":"Trade","execQty":"0.005","execValue":"210",
                           "execFee":"0.21","feeCurrency":"USDT","execTime":"${ms(2.days)}","execId":"e2","orderId":"o2"}""",
                ),
            "/v5/asset/deposit/query-record" to
                listOf(
                    ms(Duration.ZERO) to
                        """{"id":"d1","coin":"USDT","chain":"ETH","amount":"1000","status":3,"successAt":"${ms(Duration.ZERO)}",
                           "txID":"0xdeposit","fromAddress":"0xsender"}""",
                    ms(1.hours) to
                        """{"id":"d2","coin":"USDT","chain":"ETH","amount":"999","status":1,"successAt":"${ms(1.hours)}"}""",
                ),
            "/v5/asset/withdraw/query-record" to
                listOf(
                    ms(4.days) to
                        """{"withdrawId":"w1","coin":"BTC","chain":"BTC","amount":"0.01","withdrawFee":"0.0005",
                           "status":"success","createTime":"${ms(4.days)}","toAddress":"bc1qdest","txID":"0xwithdraw"}""",
                ),
            "/v5/asset/deposit/query-internal-record" to
                listOf(
                    ms(2.hours) to
                        """{"id":"i1","type":1,"coin":"USDT","amount":"50","status":2,"address":"friend@example.com",
                           "createdTime":"${ms(2.hours) / 1000}"}""",
                ),
            "/v5/asset/exchange/query-convert-history" to
                listOf(
                    ms(1.days + 1.hours) to
                        """{"exchangeTxId":"cv1","fromCoin":"USDT","toCoin":"ETH","fromAmount":"100","toAmount":"0.04",
                           "exchangeStatus":"success","createdAt":"${ms(1.days + 1.hours)}"}""",
                ),
            "/v5/earn/order?FlexibleSaving" to
                listOf(
                    ms(1.days + 2.hours) to
                        """{"orderId":"s1","coin":"USDT","orderValue":"200","orderType":"Stake","status":"Success",
                           "createdAt":"${ms(1.days + 2.hours)}"}""",
                    ms(1.days + 3.hours) to
                        """{"orderId":"s2","coin":"USDT","orderValue":"999","orderType":"Stake","status":"Fail",
                           "createdAt":"${ms(1.days + 3.hours)}"}""",
                    ms(3.days) to
                        """{"orderId":"r1","coin":"USDT","orderValue":"50","orderType":"Redeem","status":"Success",
                           "createdAt":"${ms(3.days)}"}""",
                ),
            "/v5/earn/yield?FlexibleSaving" to
                listOf(
                    ms(3.days + 1.hours) to
                        """{"id":"y1","coin":"USDT","amount":"0.5","status":"Success","createdAt":"${ms(3.days + 1.hours)}"}""",
                ),
            "/v5/account/transaction-log?linear" to
                listOf(
                    ms(5.days) to
                        """{"id":"l1","type":"TRADE","currency":"USDT","change":"-0.3","transactionTime":"${ms(5.days)}"}""",
                    ms(6.days) to
                        """{"id":"l2","type":"SETTLEMENT","currency":"USDT","change":"1.2","transactionTime":"${ms(6.days)}"}""",
                    ms(6.days + 1.hours) to
                        """{"id":"l3","type":"TRANSFER_IN","currency":"USDT","change":"100","transactionTime":"${ms(6.days + 1.hours)}"}""",
                ),
        )

    @Test
    fun `downloads every endpoint page then imports trades transfers earn and derivatives`() =
        runTest {
            val strategy = bybitStrategy()
            val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-machine", "Test OS"))
            val credentialId = apiConnection("Bybit", t0)
            val sessionId = repositories.importEngine.createApiSession(deviceId, t0, credentialId)
            val traffic = download(strategy, sessionId)

            assertEquals(emptyList(), traffic.unsigned, "every private request must carry the four X-BAPI headers")
            assertTrue(traffic.queries.any { "cursor=p%3A1" in it }, "the cursor is sent back once-encoded: ${traffic.queries}")
            assertTrue(traffic.queries.none { "%25" in it }, "no query is double-encoded")
            assertEquals(1, traffic.queries.count { "category=inverse" in it }, "a retCode error abandons only that endpoint")

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

            // USDT: +1000 deposit +50 internal -800 buy +210 sell -0.21 fee -100 convert -200 stake +50 redeem
            //       +0.5 yield -0.3 +1.2 derivatives.
            // BTC: +0.02 buy -0.00002 fee -0.005 sell -0.01 withdrawal -0.0005 withdrawal fee. ETH: +0.04 convert.
            val expected = mapOf("USDT" to "211.19", "BTC" to "0.00448", "ETH" to "0.04")
            assertEquals(expected, balances("Bybit"))
            assertEquals(mapOf("USDT" to "150"), balances("Bybit Earn"), "stake out, redeem back")
            assertEquals(mapOf("USDT" to "-0.5"), balances("Bybit Earn Rewards"))
            assertEquals(mapOf("USDT" to "-0.9"), balances("Bybit Derivatives"))

            val bybit =
                assertNotNull(
                    repositories.accountRepository
                        .getAllAccounts()
                        .first()
                        .firstOrNull { it.name == "Bybit" },
                )
            // Two spot fills plus the convert.
            assertEquals(
                3,
                repositories.tradeRepository
                    .getTradesByAccount(bybit.id)
                    .first()
                    .size,
            )

            import()
            assertEquals(expected, balances("Bybit"), "re-import must not double-book")
            assertEquals(
                3,
                repositories.tradeRepository
                    .getTradesByAccount(bybit.id)
                    .first()
                    .size,
            )
        }

    private suspend fun bybitStrategy() =
        repositories.apiImportStrategyRepository
            .getAllStrategies()
            .first()
            .single { it.name == "Bybit" }

    private class Traffic {
        val queries = mutableListOf<String>()
        val unsigned = mutableListOf<String>()
    }

    private suspend fun download(
        strategy: ApiImportStrategy,
        sessionId: ApiSessionId,
    ): Traffic {
        val traffic = Traffic()
        val signingHeaders = listOf("X-BAPI-API-KEY", "X-BAPI-TIMESTAMP", "X-BAPI-SIGN", "X-BAPI-RECV-WINDOW")
        val apiClient =
            createApiClient(
                trafficRecorder = ApiSessionTrafficRecorder(sessionId = sessionId, importEngine = repositories.importEngine),
                engine =
                    MockEngine { request ->
                        val path = request.url.encodedPath
                        val params = request.url.parameters
                        val body =
                            if (path == "/v5/market/time") {
                                """{"retCode":0,"retMsg":"OK","result":{},"time":${Clock.System.now().toEpochMilliseconds()}}"""
                            } else {
                                traffic.queries += "$path?${request.url.encodedQuery}"
                                if (signingHeaders.any { request.headers[it].isNullOrBlank() }) traffic.unsigned += path
                                if (params["category"] == "inverse") {
                                    """{"retCode":10001,"retMsg":"params error","result":{}}"""
                                } else {
                                    page(path, params["category"], params["startTime"], params["endTime"], params["cursor"])
                                }
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
            apiKey = "test-key",
            apiSecret = "test-secret",
            apiSessionRepository = repositories.apiSessionRepository,
            sessionId = sessionId,
            strategy = strategy,
            importEngine = repositories.importEngine,
            rateLimitMillis = 0,
        )
        return traffic
    }

    /**
     * One row per page, like a tiny page size: the rows of [path] inside the requested window, the one at
     * the decoded cursor `p:N`, with a pre-encoded cursor to the next row while any remain.
     */
    private fun page(
        path: String,
        category: String?,
        startTime: String?,
        endTime: String?,
        cursor: String?,
    ): String {
        val all = rows[category?.let { "$path?$it" } ?: path] ?: rows[path] ?: error("unexpected request $path")
        val inWindow =
            all
                .filter { (ts, _) ->
                    (startTime == null || ts >= startTime.toLong()) && (endTime == null || ts <= endTime.toLong())
                }.map { it.second }
        // Live Bybit returns Earn yield under "list" too, despite the docs saying "yield".
        val arrayKey = if (path.startsWith("/v5/asset/deposit") || path.startsWith("/v5/asset/withdraw")) "rows" else "list"
        if (path == "/v5/asset/exchange/query-convert-history") {
            return """{"retCode":0,"retMsg":"OK","result":{"$arrayKey":[${inWindow.joinToString(",")}]}}"""
        }
        val index = cursor?.removePrefix("p:")?.toInt() ?: 0
        val item = inWindow.getOrNull(index)
        val next = if (index + 1 < inWindow.size) "p%3A${index + 1}" else ""
        return """{"retCode":0,"retMsg":"OK","result":{"$arrayKey":[${item.orEmpty()}],"nextPageCursor":"$next"}}"""
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
}
