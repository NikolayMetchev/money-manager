package com.moneymanager.ui.wise

import com.moneymanager.apiimporter.ApiSessionImportException
import com.moneymanager.apiimporter.downloadApiSessionAccounts
import com.moneymanager.apiimporter.downloadApiSessionTransactions
import com.moneymanager.domain.model.ApiCredentialId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.importengineapi.createApiCredential
import com.moneymanager.importengineapi.createApiSession
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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

private const val PROFILE_ID = "111"
private const val BALANCE_ID = "222"

private const val INCREMENTAL_PROFILES_JSON =
    """[ { "id": $PROFILE_ID, "type": "personal", "details": { "firstName": "Ada", "lastName": "Lovelace" } } ]"""

private const val INCREMENTAL_BALANCES_JSON =
    """[ { "id": $BALANCE_ID, "currency": "GBP", "name": null, "type": "STANDARD" } ]"""

private val INCREMENTAL_STATEMENT_JSON =
    """
    {
      "transactions": [
        {
          "type": "CREDIT",
          "date": "2024-06-02T14:30:00.000Z",
          "amount": { "value": 500.00, "currency": "GBP" },
          "totalFees": { "value": 0, "currency": "GBP" },
          "details": { "type": "DEPOSIT", "description": "Received from Alice", "senderName": "Alice Example" },
          "referenceNumber": "TRANSFER-1001"
        }
      ]
    }
    """.trimIndent()

/**
 * A second download of the same credential must fetch only the tail its predecessor did not cover.
 * Exercised against the built-in Wise strategy because it uses `DATE_WINDOW` pagination, where the
 * saving is the whole point: without a watermark every download re-sweeps `lookbackDays` of history
 * one window at a time.
 */
class IncrementalApiDownloadE2ETest : DbTest() {
    override val installBuiltInStrategies: Boolean = true

    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    private val token = "test-wise-token"

    private fun engine() =
        MockEngine { request ->
            val url = request.url.toString()
            val json =
                when {
                    url.contains("statement.json") -> INCREMENTAL_STATEMENT_JSON
                    url.contains("/balances") -> INCREMENTAL_BALANCES_JSON
                    url.contains("/v1/profiles") -> INCREMENTAL_PROFILES_JSON
                    else -> error("Unexpected request: $url")
                }
            respond(content = json, status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }

    /** Runs one full download into a fresh session and returns how many statement pages it fetched. */
    private suspend fun download(
        credentialId: ApiCredentialId,
        forceFullDownload: Boolean = false,
    ): Pair<ApiSessionId, Int> {
        val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-machine", "Test OS"))
        val strategy =
            repositories.apiImportStrategyRepository
                .getAllStrategies()
                .first()
                .single { it.name == "Wise" }
        val sessionId = repositories.importEngine.createApiSession(token, deviceId, now, credentialId)
        val watermarks = repositories.apiSessionRepository.getDownloadWatermarks(credentialId, sessionId)

        fun clientFor() =
            createApiClient(
                trafficRecorder = ApiSessionTrafficRecorder(sessionId = sessionId, importEngine = repositories.importEngine),
                engine = engine(),
            )

        downloadApiSessionAccounts(
            token = token,
            apiClient = clientFor(),
            apiSessionRepository = repositories.apiSessionRepository,
            sessionId = sessionId,
            strategy = strategy,
        )
        val result =
            downloadApiSessionTransactions(
                token = token,
                apiClient = clientFor(),
                apiSessionRepository = repositories.apiSessionRepository,
                sessionId = sessionId,
                strategy = strategy,
                importEngine = repositories.importEngine,
                watermarks = if (forceFullDownload) emptyMap() else watermarks,
                forceFullDownload = forceFullDownload,
            )
        return sessionId to result.transactionResponseCount
    }

    @Test
    fun `a second download fetches only the windows the first did not cover`() =
        runTest {
            val credentialId = repositories.importEngine.createApiCredential(token, now)

            val (_, firstPages) = download(credentialId)
            assertTrue(firstPages > 1, "the first download sweeps the full lookback in windows")

            val (_, secondPages) = download(credentialId)
            assertTrue(
                secondPages < firstPages,
                "the second download must be shorter than the first ($secondPages vs $firstPages pages)",
            )

            // Wise's built-in config uses 469-day windows over a 6-year lookback; a watermark at "now"
            // less the 7-day overlap leaves exactly the single trailing window to re-fetch.
            assertEquals(1, secondPages, "only the trailing window is re-fetched")
        }

    @Test
    fun `forcing a full download ignores the watermark`() =
        runTest {
            val credentialId = repositories.importEngine.createApiCredential(token, now)

            val (_, firstPages) = download(credentialId)
            val (_, forcedPages) = download(credentialId, forceFullDownload = true)

            assertEquals(firstPages, forcedPages, "a forced download sweeps the full lookback again")
        }

    @Test
    fun `a failed download does not advance the watermark`() =
        runTest {
            val credentialId = repositories.importEngine.createApiCredential(token, now)
            val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-machine", "Test OS"))
            val strategy =
                repositories.apiImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .single { it.name == "Wise" }
            val sessionId = repositories.importEngine.createApiSession(token, deviceId, now, credentialId)

            // The statement endpoint errors with a JSON body, which the traffic interceptor still
            // records as a request+response pair. Coverage must not follow from those rows.
            val failing =
                createApiClient(
                    trafficRecorder = ApiSessionTrafficRecorder(sessionId = sessionId, importEngine = repositories.importEngine),
                    engine =
                        MockEngine { request ->
                            val url = request.url.toString()
                            when {
                                url.contains("statement.json") ->
                                    respond(
                                        content = """{ "error": "server exploded" }""",
                                        status = HttpStatusCode.InternalServerError,
                                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                                    )
                                url.contains("/balances") ->
                                    respond(
                                        content = INCREMENTAL_BALANCES_JSON,
                                        status = HttpStatusCode.OK,
                                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                                    )
                                else ->
                                    respond(
                                        content = INCREMENTAL_PROFILES_JSON,
                                        status = HttpStatusCode.OK,
                                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                                    )
                            }
                        },
                )

            downloadApiSessionAccounts(
                token = token,
                apiClient = failing,
                apiSessionRepository = repositories.apiSessionRepository,
                sessionId = sessionId,
                strategy = strategy,
            )
            assertFailsWith<ApiSessionImportException> {
                downloadApiSessionTransactions(
                    token = token,
                    apiClient = failing,
                    apiSessionRepository = repositories.apiSessionRepository,
                    sessionId = sessionId,
                    strategy = strategy,
                    importEngine = repositories.importEngine,
                )
            }

            // The failed session stored request and response rows, but no coverage.
            assertTrue(repositories.apiSessionRepository.getResponsesBySession(sessionId).isNotEmpty())
            val probe = repositories.importEngine.createApiSession(token, deviceId, now, credentialId)
            assertEquals(emptyMap(), repositories.apiSessionRepository.getDownloadWatermarks(credentialId, probe))
        }
}
