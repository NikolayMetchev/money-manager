package com.moneymanager.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import com.moneymanager.credentialvault.CredentialBundle
import com.moneymanager.credentialvault.StoredApiCredential
import com.moneymanager.credentialvault.testing.unlockedCredentialVault
import com.moneymanager.database.di.toAppServices
import com.moneymanager.presenter.error.ProvideSchemaAwareScope
import com.moneymanager.session.apiImportService
import com.moneymanager.tasks.TaskManager
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.MonzoApiFixtures
import com.moneymanager.test.database.reimports
import com.moneymanager.ui.foundation.LocalCredentialVault
import com.moneymanager.ui.foundation.LocalImportEngine
import com.moneymanager.ui.screens.apistrategy.ApiSessionsScreen
import com.moneymanager.ui.tasks.LocalTaskManager
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * "Download all" only downloads and "Import all" only imports, asking for new counterparties' names with the
 * same dialog a card's import uses; "Import all" is disabled while nothing is left to import.
 */
@OptIn(ExperimentalTestApi::class)
class ApiSessionsBulkButtonsUiTest : DbTest() {
    override val installBuiltInStrategies: Boolean = true

    // A Compose test, so not runDbTest: see ApiConnectionsVaultE2ETest.
    @BeforeTest
    fun openDatabase() = runTest { openTestDatabase() }

    private val monzo =
        MockEngine { request ->
            val url = request.url.toString()
            respond(
                content =
                    when {
                        "/accounts" in url -> MonzoApiFixtures.ACCOUNTS_JSON
                        "before=" in url -> MonzoApiFixtures.EMPTY_TRANSACTIONS_JSON
                        else -> MonzoApiFixtures.COUNTERPARTY_TRANSACTIONS_JSON
                    },
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

    @Test
    fun `download all then import all confirms the new counterparties`() =
        runMoneyManagerComposeUiTest {
            val vault =
                runBlocking {
                    unlockedCredentialVault(
                        CredentialBundle(
                            apiCredentials = listOf(StoredApiCredential(strategyName = "Monzo", token = "t", createdAtEpochMillis = 1)),
                        ),
                    )
                }
            val services = repositories.toAppServices(repositories.importEngine)
            runBlocking { apiConnection("Monzo", Instant.fromEpochMilliseconds(1_700_000_000_000L)) }
            setContent {
                CompositionLocalProvider(
                    LocalImportEngine provides repositories.importEngine,
                    LocalCredentialVault provides vault,
                    LocalTaskManager provides TaskManager(CoroutineScope(Dispatchers.Default)),
                ) {
                    ProvideSchemaAwareScope {
                        ApiSessionsScreen(
                            apiSessionRepository = repositories.apiSessionRepository,
                            importTimelineRepository = repositories.importTimelineRepository,
                            apiImportStrategyRepository = repositories.apiImportStrategyRepository,
                            accountAttributeRepository = repositories.accountAttributeRepository,
                            accountRepository = repositories.accountRepository,
                            currencyRepository = repositories.currencyRepository,
                            cryptoRepository = repositories.cryptoRepository,
                            maintenance = repositories.maintenanceService,
                            deviceId = services.deviceId,
                            apiImportService = services.apiImportService(vault, Mutex(), monzo),
                            reimports = repositories.reimports(),
                        )
                    }
                }
            }

            waitUntilAtLeastOneExists(hasText("Download all (1)"), timeoutMillis = 10_000)
            onNodeWithText("Import all (0)").assertIsNotEnabled()

            onNodeWithText("Download all (1)").performClick()
            waitUntilAtLeastOneExists(hasText("Import all (1)"), timeoutMillis = 10_000)
            onNodeWithText("Import all (1)").assertIsEnabled().performClick()

            waitUntilAtLeastOneExists(hasText("Confirm Counterparties"), timeoutMillis = 10_000)
            onNodeWithText("Create and Import").performClick()
            waitUntilDoesNotExist(hasText("Confirm Counterparties"), timeoutMillis = 10_000)
            waitForIdle()

            waitUntilAtLeastOneExists(hasText("Import all (0)"), timeoutMillis = 10_000)
            onNodeWithText("Import all (0)").assertIsNotEnabled()
            val imported = runBlocking { repositories.apiSessionRepository.getImportedSessionRevisions() }
            assertTrue(imported.isNotEmpty(), "Import all imported the downloaded session")
            val accounts = runBlocking { repositories.accountRepository.getAllAccounts().first() }
            assertTrue(accounts.any { it.name == "Alice" }, "the counterparty's account is created: ${accounts.map { it.name }}")
        }
}
