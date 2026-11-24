package com.moneymanager.ui.starling

import com.moneymanager.apiimporter.downloadApiSessionAccountIdentifiers
import com.moneymanager.apiimporter.downloadApiSessionAccounts
import com.moneymanager.apiimporter.downloadApiSessionTransactions
import com.moneymanager.apiimporter.importApiSessionTransactions
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.rest.ApiSessionTrafficRecorder
import com.moneymanager.rest.createApiClient
import com.moneymanager.test.database.DbTest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

private const val ACCOUNT_UID = "11111111-1111-1111-1111-111111111111"
private const val CATEGORY_UID = "22222222-2222-2222-2222-222222222222"

private val ACCOUNTS_JSON =
    """
{"accounts":[{"accountUid":"$ACCOUNT_UID","accountType":"PRIMARY","defaultCategory":"$CATEGORY_UID",
"currency":"GBP","createdAt":"2021-01-01T00:00:00.000Z","name":"Personal"}]}
    """.trimIndent()

private const val IDENTIFIERS_JSON =
    """{"accountIdentifier":"55556666","bankIdentifier":"099999","iban":"GB00SRLG09999955556666","bic":"SRLGGB2L"}"""

/** One good item and one whose currency the database has never heard of. */
private val FEED_WITH_UNKNOWN_CURRENCY_JSON =
    """
{"feedItems":[
{"feedItemUid":"f1","categoryUid":"$CATEGORY_UID","amount":{"currency":"GBP","minorUnits":1250},"direction":"OUT",
"transactionTime":"2024-06-03T09:15:00.000Z","reference":"COFFEE SHOP","counterPartyName":"Coffee Shop"},
{"feedItemUid":"f2","categoryUid":"$CATEGORY_UID","amount":{"currency":"QQQ","minorUnits":900},"direction":"OUT",
"transactionTime":"2024-06-04T09:15:00.000Z","reference":"MYSTERY","counterPartyName":"Mystery Shop"}
]}
    """.trimIndent()

class StarlingImportErrorsE2ETest : DbTest() {
    override val installBuiltInStrategies: Boolean = true

    private fun mockEngine() =
        MockEngine { request ->
            val url = request.url.toString()
            val json =
                when {
                    url.contains("/identifiers") -> IDENTIFIERS_JSON
                    url.contains("/feed/account/") -> FEED_WITH_UNKNOWN_CURRENCY_JSON
                    url.contains("/api/v2/accounts") -> ACCOUNTS_JSON
                    else -> error("Unexpected request: $url")
                }
            respond(content = json, status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }

    @Test
    fun `an item in an unknown currency fails on its own and the rest still import`() =
        runDbTest {
            val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-machine", "Test OS"))
            val sessionId =
                repositories.apiSessionRepository.createSession(deviceId, Instant.fromEpochMilliseconds(1_700_000_000_000L), null)
            val strategy =
                repositories.apiImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .single { it.name == "Starling" }
            val apiClient =
                createApiClient(
                    trafficRecorder = ApiSessionTrafficRecorder(sessionId = sessionId, importEngine = repositories.importEngine),
                    engine = mockEngine(),
                )

            downloadApiSessionAccounts(
                token = "test-starling-token",
                apiClient = apiClient,
                apiSessionRepository = repositories.apiSessionRepository,
                sessionId = sessionId,
                strategy = strategy,
            )
            downloadApiSessionTransactions(
                token = "test-starling-token",
                apiClient = apiClient,
                apiSessionRepository = repositories.apiSessionRepository,
                sessionId = sessionId,
                strategy = strategy,
                importEngine = repositories.importEngine,
            )
            val result =
                importApiSessionTransactions(
                    apiSessionRepository = repositories.apiSessionRepository,
                    currencyRepository = repositories.currencyRepository,
                    sessionId = sessionId,
                    strategy = strategy,
                    importEngine = repositories.importEngine,
                )

            assertEquals(1, result.transactionCount)
            assertEquals(1, result.errorCount)
        }

    @Test
    fun `transactions download and import against accounts held in an earlier session`() =
        runDbTest {
            val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-machine", "Test OS"))
            val at = Instant.fromEpochMilliseconds(1_700_000_000_000L)
            val accountsSession = repositories.apiSessionRepository.createSession(deviceId, at, null)
            val feedSession = repositories.apiSessionRepository.createSession(deviceId, at, null)
            val strategy =
                repositories.apiImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .single { it.name == "Starling" }
            val apiClient =
                createApiClient(
                    trafficRecorder = ApiSessionTrafficRecorder(sessionId = accountsSession, importEngine = repositories.importEngine),
                    engine = mockEngine(),
                )
            val feedClient =
                createApiClient(
                    trafficRecorder = ApiSessionTrafficRecorder(sessionId = feedSession, importEngine = repositories.importEngine),
                    engine = mockEngine(),
                )

            downloadApiSessionAccounts(
                token = "test-starling-token",
                apiClient = apiClient,
                apiSessionRepository = repositories.apiSessionRepository,
                sessionId = accountsSession,
                strategy = strategy,
            )
            downloadApiSessionTransactions(
                token = "test-starling-token",
                apiClient = feedClient,
                apiSessionRepository = repositories.apiSessionRepository,
                sessionId = feedSession,
                strategy = strategy,
                importEngine = repositories.importEngine,
                accountsSessionId = accountsSession,
            )
            val result =
                importApiSessionTransactions(
                    apiSessionRepository = repositories.apiSessionRepository,
                    currencyRepository = repositories.currencyRepository,
                    sessionId = feedSession,
                    accountsSessionId = accountsSession,
                    strategy = strategy,
                    importEngine = repositories.importEngine,
                )

            assertEquals(1, result.transactionCount)
        }

    @Test
    fun `repeating a download in the same session reuses what is stored and identifiers read accounts from another`() =
        runDbTest {
            val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-machine", "Test OS"))
            val at = Instant.fromEpochMilliseconds(1_700_000_000_000L)
            val accountsSession = repositories.apiSessionRepository.createSession(deviceId, at, null)
            val identifiersSession = repositories.apiSessionRepository.createSession(deviceId, at, null)
            val strategy =
                repositories.apiImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .single { it.name == "Starling" }
            val client =
                createApiClient(
                    trafficRecorder = ApiSessionTrafficRecorder(sessionId = accountsSession, importEngine = repositories.importEngine),
                    engine = mockEngine(),
                )
            val identifiersClient =
                createApiClient(
                    trafficRecorder = ApiSessionTrafficRecorder(sessionId = identifiersSession, importEngine = repositories.importEngine),
                    engine = mockEngine(),
                )

            repeat(2) {
                downloadApiSessionAccounts(
                    token = "test-starling-token",
                    apiClient = client,
                    apiSessionRepository = repositories.apiSessionRepository,
                    sessionId = accountsSession,
                    strategy = strategy,
                )
                downloadApiSessionTransactions(
                    token = "test-starling-token",
                    apiClient = client,
                    apiSessionRepository = repositories.apiSessionRepository,
                    sessionId = accountsSession,
                    strategy = strategy,
                    importEngine = repositories.importEngine,
                )
            }
            val identifiers =
                downloadApiSessionAccountIdentifiers(
                    token = "test-starling-token",
                    apiClient = identifiersClient,
                    apiSessionRepository = repositories.apiSessionRepository,
                    sessionId = identifiersSession,
                    strategy = strategy,
                    accountsSessionId = accountsSession,
                )

            assertEquals(1, identifiers.accountCount)
        }
}
