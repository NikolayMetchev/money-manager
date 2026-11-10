package com.moneymanager.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import com.moneymanager.credentialvault.testing.unlockedCredentialVault
import com.moneymanager.database.di.toAppServices
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.presenter.error.ProvideSchemaAwareScope
import com.moneymanager.session.apiImportService
import com.moneymanager.tasks.TaskManager
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.reimports
import com.moneymanager.ui.foundation.LocalCredentialVault
import com.moneymanager.ui.foundation.LocalImportEngine
import com.moneymanager.ui.screens.apistrategy.ApiSessionsScreen
import com.moneymanager.ui.tasks.LocalTaskManager
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/** The API Sessions header offers exporting every session to one file and importing such a file back. */
@OptIn(ExperimentalTestApi::class)
class ApiSessionsArchiveButtonsUiTest : DbTest() {
    override val installBuiltInStrategies: Boolean = true

    // A Compose test, so not runDbTest: see ApiConnectionsVaultE2ETest.
    @BeforeTest
    fun openDatabase() = runTest { openTestDatabase() }

    @Test
    fun `the header shows Export and Import`() =
        runMoneyManagerComposeUiTest {
            val vault = runBlocking { unlockedCredentialVault() }
            val services = repositories.toAppServices(repositories.importEngine)
            val deviceId = runBlocking { repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("m", "os")) }
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
                            deviceId = deviceId,
                            apiImportService = services.apiImportService(vault, Mutex()),
                            reimports = repositories.reimports(),
                        )
                    }
                }
            }
            waitUntilAtLeastOneExists(hasText("Export"))
            onNodeWithText("Import").assertExists()
        }
}
