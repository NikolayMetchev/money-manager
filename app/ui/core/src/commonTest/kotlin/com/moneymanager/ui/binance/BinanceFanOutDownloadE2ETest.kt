package com.moneymanager.ui.binance

import com.moneymanager.apiimporter.downloadApiSessionExchange
import com.moneymanager.domain.model.DeviceInfo
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
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * End-to-end test of `ApiFanOut` resolution against the
 * built-in Binance config: the `getUserAsset` value endpoint supplies one held asset (BTC), crossed
 * with Binance's static quote-asset list produces many candidate symbols, and `exchangeInfo`'s real
 * symbol universe (mocked to contain only `BTCUSDT`) intersects that down to exactly one - so exactly
 * one `myTrades` request should be made, not one per candidate.
 */
class BinanceFanOutDownloadE2ETest : DbTest() {
    override val installBuiltInStrategies: Boolean = true

    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    private val token = "test-binance-key"

    private suspend fun binanceStrategy() =
        repositories.apiImportStrategyRepository
            .getAllStrategies()
            .first()
            .single { it.name == "Binance" }

    @Test
    fun `spot-trade fan-out queries only the symbol that survives the exchangeInfo intersection`() =
        runTest {
            val strategy = binanceStrategy()
            val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-machine", "Test OS"))
            val credentialId = repositories.importEngine.createApiCredential(token, now)
            val sessionId = repositories.importEngine.createApiSession(token, deviceId, now, credentialId)
            val watermarks = repositories.apiSessionRepository.getDownloadWatermarks(credentialId, sessionId)

            val apiClient =
                createApiClient(
                    trafficRecorder = ApiSessionTrafficRecorder(sessionId = sessionId, importEngine = repositories.importEngine),
                    engine =
                        MockEngine { request ->
                            val path = request.url.encodedPath.trimStart('/')
                            val body =
                                when {
                                    path.endsWith("asset/getUserAsset") ->
                                        """[{"asset":"BTC","free":"1","locked":"0","freeze":"0","withdrawing":"0","ipoable":"0","btcValuation":"0"}]"""
                                    path.endsWith("exchangeInfo") -> """{"symbols":[{"symbol":"BTCUSDT"}]}"""
                                    path.endsWith("fiat/orders") || path.endsWith("fiat/payments") ->
                                        """{"code":"000000","message":"success","data":[],"total":0,"success":true}"""
                                    path.endsWith(
                                        "convert/tradeFlow",
                                    ) -> """{"list":[],"startTime":0,"endTime":0,"limit":100,"moreData":false}"""
                                    // capital/deposit/hisrec, capital/withdraw/history, myTrades all return a bare array.
                                    else -> "[]"
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
                apiKey = token,
                apiSecret = "test-secret",
                apiSessionRepository = repositories.apiSessionRepository,
                sessionId = sessionId,
                strategy = strategy,
                importEngine = repositories.importEngine,
                watermarks = watermarks,
                // No pacing: the test must not sleep through the real rate-limit delay.
                rateLimitMillis = 0,
            )

            val requests = repositories.apiSessionRepository.getRequestsBySession(sessionId)
            val myTradesRequests = requests.filter { it.url.contains("api/v3/myTrades") }
            assertEquals(1, myTradesRequests.size, "exactly one symbol should survive the exchangeInfo intersection")
            assertTrue(myTradesRequests.single().url.contains("fv=BTCUSDT"), "the surviving symbol should be BTCUSDT")

            // The public exchangeInfo value endpoint is fetched but never persisted (storeResponse = false).
            assertTrue(requests.none { it.url.contains("exchangeInfo") }, "exchangeInfo must not be recorded")
        }

    @Test
    fun `a second download re-sweeps spot trades but shortens every date-windowed endpoint`() =
        runTest {
            val strategy = binanceStrategy()
            val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-machine", "Test OS"))
            val credentialId = repositories.importEngine.createApiCredential(token, now)

            suspend fun download(): Int {
                val sessionId = repositories.importEngine.createApiSession(token, deviceId, now, credentialId)
                val watermarks = repositories.apiSessionRepository.getDownloadWatermarks(credentialId, sessionId)
                val apiClient =
                    createApiClient(
                        trafficRecorder = ApiSessionTrafficRecorder(sessionId = sessionId, importEngine = repositories.importEngine),
                        engine =
                            MockEngine {
                                respond(
                                    content = "[]",
                                    status = HttpStatusCode.OK,
                                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                                )
                            },
                    )
                return downloadApiSessionExchange(
                    apiClient = apiClient,
                    signer = ApiRequestSigner(checkNotNull(strategy.config.requestSigning)),
                    apiKey = token,
                    apiSecret = "test-secret",
                    apiSessionRepository = repositories.apiSessionRepository,
                    sessionId = sessionId,
                    strategy = strategy,
                    importEngine = repositories.importEngine,
                    watermarks = watermarks,
                    rateLimitMillis = 0,
                ).transactionResponseCount
            }

            val firstPages = download()
            assertTrue(firstPages > 1, "the first download sweeps the full lookback in windows")

            val secondPages = download()
            assertTrue(secondPages < firstPages, "the second download must be shorter ($secondPages vs $firstPages)")
        }
}
