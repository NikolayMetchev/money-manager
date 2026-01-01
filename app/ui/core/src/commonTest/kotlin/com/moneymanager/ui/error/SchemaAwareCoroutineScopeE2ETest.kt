package com.moneymanager.ui.error

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
 * End-to-end test for schema-aware coroutine scope error handling.
 * Tests that when a schema error occurs in a coroutine launched with the schema-aware scope,
 * the error is caught and the DatabaseSchemaErrorDialog is displayed.
 *
 * Uses a test database with CSV import tables that have the old schema (with _row_id column
 * instead of row_index), which causes a schema error when CsvImportDetailScreen tries to
 * load the rows.
 */
@OptIn(ExperimentalTestApi::class)
class SchemaAwareCoroutineScopeE2ETest {
    private var testDbLocation: DbLocation? = null

    @AfterTest
    fun cleanup() {
        testDbLocation?.let { location ->
            deleteTestDatabase(location)
        }
        // Clear any lingering schema error state
        GlobalSchemaErrorState.clearError()
    }

    @Test
    fun schemaAwareScope_showsSchemaErrorDialog_whenCsvTableHasOldSchema() =
        runComposeUiTest {
            // Given: Copy the test database with old CSV schema to a test location
            // Note: Old databases are missing Device/Platform tables, so schema error
            // is shown at startup during device initialization
            testDbLocation = copyDatabaseFromResources("/money_manager_csv_old_schema.db")

            val databaseManager = createTestDatabaseManager()

            // When: MoneyManagerApp is initialized
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

            // Then: Schema error dialog should be displayed at startup
            // (old database is missing Device/Platform tables)
            waitUntilExactlyOneExists(hasText("Database Schema Error"), timeoutMillis = 10000)
            onNodeWithText("Database Schema Error").assertIsDisplayed()
            // The error message should mention a missing table
            onNodeWithText(text = "no such table", substring = true).assertIsDisplayed()
        }

    @Test
    fun schemaAwareScope_allowsRecovery_whenCsvTableHasOldSchema() =
        runComposeUiTest {
            // Given: Copy the test database with old schema to a test location
            // Note: Old databases are missing Device/Platform tables, so schema error
            // is shown at startup during device initialization
            testDbLocation = copyDatabaseFromResources("/money_manager_csv_old_schema.db")

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

            // Wait for schema error dialog (shown at startup due to missing tables)
            waitUntilExactlyOneExists(hasText("Database Schema Error"), timeoutMillis = 10000)

            // When: User clicks "Delete Database and Start Fresh"
            onNodeWithText("Delete Database and Start Fresh").performClick()

            // Then: Dialog should disappear and app should recover
            waitUntilDoesNotExist(hasText("Database Schema Error"), timeoutMillis = 10000)
            onNodeWithText("Database Schema Error").assertDoesNotExist()

            // App should now show normal content after database recreation
            waitUntilExactlyOneExists(hasText("Your Accounts"), timeoutMillis = 10000)
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
