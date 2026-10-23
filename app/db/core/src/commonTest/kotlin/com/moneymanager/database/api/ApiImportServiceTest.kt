package com.moneymanager.database.api

import com.moneymanager.apiimport.ApiDownloadAndImportAllResult
import com.moneymanager.apiimport.ApiDownloadOutcome
import com.moneymanager.apiimport.ApiImportService
import com.moneymanager.apiimport.ApiSessionImportStatus
import com.moneymanager.apiimport.apiDownloadTaskKey
import com.moneymanager.apiimport.apiImportTaskKey
import com.moneymanager.credentialvault.CredentialBundle
import com.moneymanager.credentialvault.CredentialVault
import com.moneymanager.credentialvault.StoredApiCredential
import com.moneymanager.credentialvault.testing.inMemoryCredentialVault
import com.moneymanager.credentialvault.testing.unlockedCredentialVault
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.ApiCredential
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.tasks.TaskManager
import com.moneymanager.tasks.TaskScope
import com.moneymanager.tasks.TaskState
import com.moneymanager.tasks.TaskStatus
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.MonzoApiFixtures
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Instant

/** A transfer to a counterparty Monzo identifies by id, whose account name a person must confirm. */
private val MONZO_COUNTERPARTY_TRANSACTIONS_JSON =
    """
    {"transactions": [
      {"id": "tx_2", "account_id": "${MonzoApiFixtures.ACCOUNT_ID}", "created": "2024-06-04T09:15:00.000Z", "amount": -2500,
       "currency": "GBP", "description": "PAYMENT TO ALICE", "merchant": null,
       "counterparty": {"id": "cp_alice_001", "name": "Alice"}}
    ]}
    """.trimIndent()

/**
 * [ApiImportService] against a real database and a mocked provider: "Download & import all" downloads and
 * imports each connection as child tasks, isolates a failing provider, and hands back sessions whose
 * counterparties need a person instead of importing them.
 */
class ApiImportServiceTest : DbTest() {
    override val installBuiltInStrategies: Boolean = true

    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private var materializedViewRefreshes = 0

    private val maintenance =
        object : Maintenance {
            override suspend fun reindex(): Duration = Duration.ZERO

            override suspend fun vacuum(): Duration = Duration.ZERO

            override suspend fun analyze(): Duration = Duration.ZERO

            override suspend fun refreshMaterializedViews(): Duration {
                materializedViewRefreshes++
                return Duration.ZERO
            }

            override suspend fun fullRefreshMaterializedViews(): Duration = Duration.ZERO
        }

    private val monzoSecrets = StoredApiCredential(strategyName = "Monzo", token = "monzo-token", createdAtEpochMillis = 1)

    private val requestedUrls = mutableListOf<String>()

    private var monzoTransactionsJson = MonzoApiFixtures.TRANSACTIONS_JSON

    /** Monzo answers from fixtures; any other host (Starling) fails with a server error. */
    private fun mockProviders(): HttpClientEngine =
        MockEngine { request ->
            val url = request.url.toString()
            requestedUrls += url
            when {
                "api.monzo.com" !in url -> {
                    respond("down", HttpStatusCode.InternalServerError)
                }

                else -> {
                    respond(
                        content =
                            when {
                                "/accounts" in url -> MonzoApiFixtures.ACCOUNTS_JSON
                                "before=" in url -> MonzoApiFixtures.EMPTY_TRANSACTIONS_JSON
                                else -> monzoTransactionsJson
                            },
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }
        }

    private fun service(
        vault: CredentialVault,
        countryCode: String? = "GB",
    ): ApiImportService =
        ApiImportService(
            apiSessionRepository = repositories.apiSessionRepository,
            apiImportStrategyRepository = repositories.apiImportStrategyRepository,
            accountRepository = repositories.accountRepository,
            accountAttributeRepository = repositories.accountAttributeRepository,
            currencyRepository = repositories.currencyRepository,
            cryptoRepository = repositories.cryptoRepository,
            passThroughAccountRepository = repositories.passThroughAccountRepository,
            maintenance = maintenance,
            importEngine = repositories.importEngine,
            credentialVault = vault,
            deviceId = deviceId(),
            httpEngine = mockProviders(),
            countryCode = { countryCode },
        )

    private fun deviceId(): DeviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-machine", "Test OS"))

    private suspend fun strategy(name: String): ApiImportStrategy =
        repositories.apiImportStrategyRepository
            .getAllStrategies()
            .first()
            .single { it.name == name }

    private suspend fun connection(strategyName: String): ApiCredential {
        val id = apiConnection(strategyName, now)
        return repositories.apiSessionRepository.getAllCredentials().single { it.id == id }
    }

    /** Runs [block] as a real top-level task and returns its value plus every task the run left behind. */
    private suspend fun <T> CoroutineScope.runTask(block: suspend TaskScope.() -> T): Pair<T, List<TaskState>> {
        val manager = TaskManager(this)
        val result = CompletableDeferred<T>()
        manager.start("test", "Test", "") {
            try {
                block().also { result.complete(it) }.toString()
            } catch (expected: Exception) {
                result.completeExceptionally(expected)
                throw expected
            }
        }
        val value = result.await()
        // The task's own state settles just after its block returns.
        manager.tasks.first { tasks -> tasks.none { it.isRunning } }
        return value to manager.tasks.value
    }

    @Test
    fun `download and import all imports each connection as child tasks and records the import`() =
        runTest {
            val monzo = connection("Monzo")
            val vault = unlockedCredentialVault(CredentialBundle(apiCredentials = listOf(monzoSecrets)))

            val (result, tasks) = backgroundScope.runTask { service(vault).downloadAndImportAll(this, listOf(monzo)) }

            assertEquals(1, result.downloadedCount)
            assertEquals(1, result.importedCount)
            assertEquals(emptyList(), result.needsAttention)
            assertEquals("Downloaded 1 of 1 connection(s). Imported 1 of 1 session(s).", result.summary())
            val session = repositories.apiSessionRepository.getSessionsByDevice(deviceId()).single()
            assertEquals(
                setOf(session.id),
                repositories.apiSessionRepository
                    .getImportedSessionRevisions()
                    .map { it.sessionId }
                    .toSet(),
                "the session is marked imported",
            )
            assertEquals(1, materializedViewRefreshes, "the import refreshes the materialized views")
            assertEquals(TaskStatus.SUCCEEDED, tasks.single { it.key == apiDownloadTaskKey(monzo.id) }.status)
            assertEquals(TaskStatus.SUCCEEDED, tasks.single { it.key == apiImportTaskKey(session.id) }.status)
        }

    @Test
    fun `one failing provider does not stop the others`() =
        runTest {
            val monzo = connection("Monzo")
            val starling = connection("Starling")
            val vault =
                unlockedCredentialVault(
                    CredentialBundle(
                        apiCredentials =
                            listOf(monzoSecrets, StoredApiCredential(strategyName = "Starling", token = "t", createdAtEpochMillis = 1)),
                    ),
                )

            val (result, tasks) = backgroundScope.runTask { service(vault).downloadAndImportAll(this, listOf(starling, monzo)) }

            assertEquals(2, result.credentialCount)
            assertEquals(1, result.downloadedCount, "Starling's server error leaves it undownloaded")
            assertEquals(listOf(starling.id, monzo.id), result.credentials.map { it.credential.id }, "in the order asked for")
            val starlingDownload = result.credentials.first()
            assertEquals("Starling", starlingDownload.strategyName)
            assertNotNull(starlingDownload.error ?: (starlingDownload.download as? ApiDownloadOutcome.NotDownloaded)?.message)
            assertTrue(
                tasks.single { it.parentId == null }.issues.any { it.startsWith("Starling: ") },
                "the failure is reported on the run",
            )
            val monzoSession = repositories.apiSessionRepository.getSessionsByDevice(deviceId()).single { it.credentialId == monzo.id }
            assertTrue(
                monzoSession.id in repositories.apiSessionRepository.getImportedSessionRevisions().map { it.sessionId },
                "Monzo still downloads and imports",
            )
        }

    @Test
    fun `download and import all can only download and can leave the view refresh to the caller`() =
        runTest {
            val monzo = connection("Monzo")
            val vault = unlockedCredentialVault(CredentialBundle(apiCredentials = listOf(monzoSecrets)))
            val service = service(vault)

            val (downloaded, _) = backgroundScope.runTask { service.downloadAndImportAll(this, listOf(monzo), importSessions = false) }

            assertEquals(1, downloaded.downloadedCount)
            assertEquals(emptyList(), downloaded.sessions, "nothing is queued for import")
            assertEquals(emptySet(), repositories.apiSessionRepository.getImportedSessionRevisions())

            val (imported, _) = backgroundScope.runTask { service.downloadAndImportAll(this, listOf(monzo), refreshViews = false) }

            assertEquals(2, imported.sessionCount, "the earlier download's session is imported too")
            assertEquals(listOf(ApiSessionImportStatus.IMPORTED, ApiSessionImportStatus.IMPORTED), imported.sessions.map { it.status })
            assertEquals(
                1,
                imported.sessions
                    .first()
                    .result
                    ?.transactionCount,
            )
            assertEquals(0, materializedViewRefreshes, "the caller refreshes once itself")
        }

    @Test
    fun `a session already being imported elsewhere comes back as failed and the queue moves on`() =
        runTest {
            val monzo = connection("Monzo")
            val vault = unlockedCredentialVault(CredentialBundle(apiCredentials = listOf(monzoSecrets)))
            val service = service(vault)
            backgroundScope.runTask { service.downloadAndImportAll(this, listOf(monzo), importSessions = false) }
            val earlier = repositories.apiSessionRepository.getSessionsByDevice(deviceId()).single()
            val manager = TaskManager(backgroundScope)
            val release = CompletableDeferred<Unit>()
            manager.start(apiImportTaskKey(earlier.id), "Elsewhere", "") {
                release.await()
                "done"
            }
            val result = CompletableDeferred<ApiDownloadAndImportAllResult>()

            manager.start("test", "Test", "") {
                result.complete(service.downloadAndImportAll(this, listOf(monzo)))
                "done"
            }

            val sessions = result.await().sessions
            release.complete(Unit)
            val failed = sessions.single { it.session.id == earlier.id }
            assertEquals(ApiSessionImportStatus.FAILED, failed.status)
            assertEquals("an import of it was already running", failed.error)
            assertEquals(
                listOf(ApiSessionImportStatus.IMPORTED),
                sessions.filter { it.session.id != earlier.id }.map { it.status },
                "the next session still imports",
            )
        }

    @Test
    fun `a session whose counterparties need confirming comes back as needing attention`() =
        runTest {
            monzoTransactionsJson = MONZO_COUNTERPARTY_TRANSACTIONS_JSON
            val monzo = connection("Monzo")
            val vault = unlockedCredentialVault(CredentialBundle(apiCredentials = listOf(monzoSecrets)))

            val (result, _) = backgroundScope.runTask { service(vault).downloadAndImportAll(this, listOf(monzo)) }

            assertEquals(0, result.importedCount)
            val attention = result.needsAttention.single()
            assertEquals(listOf("cp_alice_001"), attention.counterparties.map { it.counterpartyId })
            assertEquals(emptySet(), repositories.apiSessionRepository.getImportedSessionRevisions(), "nothing is imported")
        }

    @Test
    fun `a download with no saved secrets creates no session`() =
        runTest {
            val monzo = connection("Monzo")
            val vault = unlockedCredentialVault()

            val (outcome, _) = backgroundScope.runTask { service(vault).download(this, monzo, forceFull = false) }

            assertIs<ApiDownloadOutcome.NotDownloaded>(outcome)
            assertTrue("No Monzo credentials" in outcome.message)
            assertEquals(emptyList(), repositories.apiSessionRepository.getSessionsByDevice(deviceId()))
        }

    @Test
    fun `an incremental download only asks for what the last session did not reach`() =
        runTest {
            val monzo = connection("Monzo")
            val vault = unlockedCredentialVault(CredentialBundle(apiCredentials = listOf(monzoSecrets)))
            val service = service(vault)
            backgroundScope.runTask { service.download(this, monzo, forceFull = false) }
            val firstRunRequests = requestedUrls.toList()

            requestedUrls.clear()
            backgroundScope.runTask { service.download(this, monzo, forceFull = false) }
            val incremental = requestedUrls.toList()
            requestedUrls.clear()
            backgroundScope.runTask { service.download(this, monzo, forceFull = true) }
            val forced = requestedUrls.toList()

            assertEquals(firstRunRequests.map { it.substringBefore('?') }, forced.map { it.substringBefore('?') })
            assertTrue(incremental != forced, "the incremental download starts from the watermark: $incremental")
        }

    @Test
    fun `ensure connections creates rows only once the vault is unlocked`() =
        runTest {
            assertEquals(0, service(inMemoryCredentialVault()).ensureConnections(), "a locked vault is never prompted")
            assertEquals(emptyList(), repositories.apiSessionRepository.getAllCredentials())

            val created = service(unlockedCredentialVault(CredentialBundle(apiCredentials = listOf(monzoSecrets)))).ensureConnections()

            assertEquals(1, created)
            val row = assertNotNull(repositories.apiSessionRepository.getAllCredentials().singleOrNull())
            assertEquals(strategy("Monzo").id, row.strategyId)
        }

    @Test
    fun `the statement region gate follows the device country`() =
        runTest {
            val wise = strategy("Wise")
            val vault = unlockedCredentialVault()

            assertNull(service(vault, countryCode = "US").transactionsBlockReason(wise))
            val blocked = assertNotNull(service(vault).transactionsBlockReason(wise))
            assertTrue("(GB)" in blocked && "AU, CA, MY, NZ, SG, US" in blocked, blocked)
            assertNull(service(vault).transactionsBlockReason(strategy("Monzo")))
        }
}
