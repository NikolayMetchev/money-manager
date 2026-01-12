package com.moneymanager.ui.components

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilDoesNotExist
import androidx.compose.ui.test.waitUntilExactlyOneExists
import com.moneymanager.database.DatabaseManager
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.test.database.copyDatabaseFromResources
import com.moneymanager.test.database.createTestDatabaseManager
import com.moneymanager.test.database.deleteTestDatabase
import com.moneymanager.ui.test.TestMoneyManagerApp
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * End-to-end test for DatabaseSchemaErrorDialog.
 * Tests that when a database with schema errors is opened, the error dialog is displayed.
 *
 * Uses the real platform-specific DatabaseManager and a test database with schema issues
 * located at commonTest/resources/money_manager.db
 */
@OptIn(ExperimentalTestApi::class)
class DatabaseSchemaErrorDialogE2ETest {
    private var testDbLocation: DbLocation? = null

    @AfterTest
    fun cleanup() {
        testDbLocation?.let { location ->
            deleteTestDatabase(location)
        }
    }

    @Test
    fun moneyManagerApp_showsSchemaErrorDialog_whenDatabaseHasSchemaError() =
        runComposeUiTest {
            // Given: Copy the problematic test database to a test location
            testDbLocation = copyDatabaseFromResources("/money_manager.db")

            // Use real platform-specific DatabaseManager
            val databaseManager = createTestDatabaseManager()

            // When: MoneyManagerApp is initialized with the problematic database
            setContent {
                TestMoneyManagerApp(
                    databaseManager =
                        TestDatabaseManager(
                            databaseManager = databaseManager,
                            testLocation = testDbLocation!!,
                        ),
                    appVersion = AppVersion("1.0.0-test"),
                )
            }

            // Wait for LaunchedEffect to run and error to be caught
            waitForIdle()

            // Then: DatabaseSchemaErrorDialog should be displayed
            // Use waitUntilExactlyOneExists to give the dialog time to appear
            waitUntilExactlyOneExists(hasText("Database Schema Error"), timeoutMillis = 10000)
            onNodeWithText("Database Schema Error").assertIsDisplayed()
            onNodeWithText(
                text = "The database at the following location has an incompatible schema.",
                substring = true,
            ).assertIsDisplayed()
            onNodeWithText("Database location:").assertIsDisplayed()
            onNodeWithText("Backup Database and Create New").assertIsDisplayed()
            onNodeWithText("Delete Database and Start Fresh").assertIsDisplayed()

            // Error message should mention the specific issue
            onNodeWithText(text = "Error details:", substring = false).assertIsDisplayed()
        }

    @Test
    fun databaseSchemaErrorDialog_allowsBackupAndCreateNew() =
        runComposeUiTest {
            // Given: Copy the problematic test database to a test location
            testDbLocation = copyDatabaseFromResources("/money_manager.db")
            val databaseManager = createTestDatabaseManager()

            setContent {
                TestMoneyManagerApp(
                    databaseManager =
                        TestDatabaseManager(
                            databaseManager = databaseManager,
                            testLocation = testDbLocation!!,
                        ),
                    appVersion = AppVersion("1.0.0-test"),
                )
            }

            // Wait for dialog to appear
            waitUntilExactlyOneExists(hasText("Database Schema Error"), timeoutMillis = 10000)

            // Verify dialog is shown
            onNodeWithText("Database Schema Error").assertIsDisplayed()

            // When: User clicks "Backup Database and Create New"
            onNodeWithText("Backup Database and Create New").performClick()

            // Wait for operation to complete and dialog to disappear
            waitUntilDoesNotExist(hasText("Database Schema Error"), timeoutMillis = 10000)
            waitForIdle()

            // Then: Dialog should be gone
            onNodeWithText("Database Schema Error").assertDoesNotExist()
        }

    @Test
    fun databaseSchemaErrorDialog_allowsDeleteAndCreateNew() =
        runComposeUiTest {
            // Given: Copy the problematic test database to a test location
            testDbLocation = copyDatabaseFromResources("/money_manager.db")
            val databaseManager = createTestDatabaseManager()

            setContent {
                TestMoneyManagerApp(
                    databaseManager =
                        TestDatabaseManager(
                            databaseManager = databaseManager,
                            testLocation = testDbLocation!!,
                        ),
                    appVersion = AppVersion("1.0.0-test"),
                )
            }

            // Wait for dialog to appear
            waitUntilExactlyOneExists(hasText("Database Schema Error"), timeoutMillis = 10000)

            // Verify dialog is shown
            onNodeWithText("Database Schema Error").assertIsDisplayed()

            // When: User clicks "Delete Database and Start Fresh"
            onNodeWithText("Delete Database and Start Fresh").performClick()

            // Wait for operation to complete and dialog to disappear
            waitUntilDoesNotExist(hasText("Database Schema Error"), timeoutMillis = 10000)
            waitForIdle()

            // Then: Dialog should be gone
            onNodeWithText("Database Schema Error").assertDoesNotExist()
        }
}

/**
 * Wrapper around DatabaseManager that overrides getDefaultLocation
 * to return the test database location.
 */
private class TestDatabaseManager(
    private val databaseManager: DatabaseManager,
    private val testLocation: DbLocation,
) : DatabaseManager by databaseManager {
    override fun getDefaultLocation(): DbLocation = testLocation
}
