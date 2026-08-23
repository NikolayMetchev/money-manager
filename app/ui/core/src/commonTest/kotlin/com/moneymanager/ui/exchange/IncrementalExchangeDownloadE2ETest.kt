package com.moneymanager.ui.exchange

import com.moneymanager.apiimporter.downloadApiSessionExchange
import com.moneymanager.domain.model.ApiCredentialId
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

/** Kraken's envelope: no errors, an empty keyed result, and a zero total so the offset loop ends. */
private const val EMPTY_RESULT_JSON = """{ "error": [], "result": { "count": 0 } }"""

/**
 * The signed-exchange counterpart of [com.moneymanager.ui.wise.IncrementalApiDownloadE2ETest], run
 * against Kraken specifically: two of its data endpoints share the `Ledgers` path and differ only by a
 * query param signed into the POST body, so this also pins that they get distinct watermark keys — a
 * collision there would let one endpoint's coverage silently suppress the other's download.
 */
class IncrementalExchangeDownloadE2ETest : DbTest() {
    override val installBuiltInStrategies: Boolean = true

    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    private val token = "test-kraken-key"

    private suspend fun krakenStrategy() =
        repositories.apiImportStrategyRepository
            .getAllStrategies()
            .first()
            .single { it.name == "Kraken" }

    private suspend fun download(
        credentialId: ApiCredentialId,
        forceFullDownload: Boolean = false,
    ): Int {
        val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-machine", "Test OS"))
        val strategy = krakenStrategy()
        val sessionId = repositories.importEngine.createApiSession(token, deviceId, now, credentialId)
        val watermarks = repositories.apiSessionRepository.getDownloadWatermarks(credentialId, sessionId)
        val apiClient =
            createApiClient(
                trafficRecorder = ApiSessionTrafficRecorder(sessionId = sessionId, importEngine = repositories.importEngine),
                engine =
                    MockEngine {
                        respond(
                            content = EMPTY_RESULT_JSON,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
            )

        return downloadApiSessionExchange(
            apiClient = apiClient,
            signer = ApiRequestSigner(checkNotNull(strategy.config.requestSigning)),
            apiKey = token,
            // Kraken base64-decodes the secret before signing, so it has to be valid base64.
            apiSecret = "c2VjcmV0LWtleS1mb3ItdGVzdGluZw==",
            apiSessionRepository = repositories.apiSessionRepository,
            sessionId = sessionId,
            strategy = strategy,
            importEngine = repositories.importEngine,
            watermarks = watermarks,
            forceFullDownload = forceFullDownload,
            // No pacing: the test must not sleep through the real rate-limit delay.
            rateLimitMillis = 0,
        ).transactionResponseCount
    }

    @Test
    fun `a second exchange download fetches only the trailing windows`() =
        runTest {
            val credentialId = repositories.importEngine.createApiCredential(token, now)

            val firstPages = download(credentialId)
            assertTrue(firstPages > 1, "the first download sweeps the full lookback in 90-day windows")

            val secondPages = download(credentialId)
            assertTrue(secondPages < firstPages, "the second download must be shorter ($secondPages vs $firstPages)")
        }

    @Test
    fun `endpoints sharing a path get their own watermark`() =
        runTest {
            val credentialId = repositories.importEngine.createApiCredential(token, now)
            download(credentialId)

            val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-machine", "Test OS"))
            val probeSession = repositories.importEngine.createApiSession(token, deviceId, now, credentialId)
            val watermarks = repositories.apiSessionRepository.getDownloadWatermarks(credentialId, probeSession)

            assertEquals(
                krakenStrategy().config.dataEndpoints.size,
                watermarks.size,
                "every data endpoint gets its own watermark, including the two that share the Ledgers path",
            )
        }

    @Test
    fun `forcing a full exchange download ignores the watermark`() =
        runTest {
            val credentialId = repositories.importEngine.createApiCredential(token, now)

            val firstPages = download(credentialId)

            assertEquals(firstPages, download(credentialId, forceFullDownload = true), "a forced download re-sweeps")
        }
}
