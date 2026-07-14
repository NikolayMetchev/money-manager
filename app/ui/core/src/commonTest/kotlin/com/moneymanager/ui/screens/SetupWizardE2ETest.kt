package com.moneymanager.ui.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import com.moneymanager.database.DatabaseManager
import com.moneymanager.di.database.DatabaseComponent
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.test.database.createTestDatabaseLocation
import com.moneymanager.test.database.createTestDatabaseManager
import com.moneymanager.test.database.deleteTestDatabase
import com.moneymanager.ui.screens.setup.SetupWizardStep
import com.moneymanager.ui.test.MoneyManagerTestApp
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The setup wizard takes over the app on a database that hasn't been set up, and finishing it (or skipping
 * it) records that per-database so the app is usable and the wizard stays away.
 *
 * `MoneyManagerTestApp` wires no catalog/sync/file-source controllers, so the only navigable step here is
 * the currency one — step composition itself is covered by `SetupWizardStepTest`.
 */
@OptIn(ExperimentalTestApi::class)
class SetupWizardE2ETest {
    private var testDbLocation: DbLocation? = null

    @AfterTest
    fun cleanup() {
        testDbLocation?.let { location -> deleteTestDatabase(location) }
    }

    @Test
    fun setupWizard_runsOnAFreshDatabase_andFinishingRecordsItAndOpensTheApp() =
        runMoneyManagerComposeUiTest {
            testDbLocation = createTestDatabaseLocation()
            val databaseManager = createTestDatabaseManager()

            setContent {
                MoneyManagerTestApp(
                    databaseManager = SetupTestDatabaseManager(databaseManager, testDbLocation!!),
                    appVersion = AppVersion("1.0.0-test"),
                    completeSetupWizard = false,
                )
            }

            waitForIdle()
            waitUntilAtLeastOneExists(hasText("Set up Money Manager"), timeoutMillis = 20000)
            onNodeWithText(SetupWizardStep.CURRENCY.subtitle).assertExists()

            onNodeWithText("Finish").performClick()

            // The wizard gets out of the way and the app proper appears.
            waitUntilDoesNotExist(hasText("Set up Money Manager"), timeoutMillis = 20000)
            waitUntilAtLeastOneExists(hasText("Your Accounts"), timeoutMillis = 20000)
            waitForIdle()

            assertTrue(readSetupWizardCompleted(databaseManager, testDbLocation!!))
        }

    @Test
    fun setupWizard_canBeSkippedEntirely() =
        runMoneyManagerComposeUiTest {
            testDbLocation = createTestDatabaseLocation()
            val databaseManager = createTestDatabaseManager()

            setContent {
                MoneyManagerTestApp(
                    databaseManager = SetupTestDatabaseManager(databaseManager, testDbLocation!!),
                    appVersion = AppVersion("1.0.0-test"),
                    completeSetupWizard = false,
                )
            }

            waitForIdle()
            waitUntilAtLeastOneExists(hasText("Set up Money Manager"), timeoutMillis = 20000)

            onNodeWithText("Skip setup").performClick()

            waitUntilDoesNotExist(hasText("Set up Money Manager"), timeoutMillis = 20000)
            waitUntilAtLeastOneExists(hasText("Your Accounts"), timeoutMillis = 20000)
            waitForIdle()

            // Skipping is remembered too: the wizard must not ambush the user on every launch.
            assertTrue(readSetupWizardCompleted(databaseManager, testDbLocation!!))
        }

    private fun readSetupWizardCompleted(
        databaseManager: DatabaseManager,
        location: DbLocation,
    ): Boolean =
        runBlocking {
            val component = DatabaseComponent.create(databaseManager.openDatabase(location))
            component.settingsRepository.isSetupWizardCompleted().first()
        }
}

private class SetupTestDatabaseManager(
    private val databaseManager: DatabaseManager,
    private val testLocation: DbLocation,
) : DatabaseManager by databaseManager {
    override fun getDefaultLocation(): DbLocation = testLocation
}
