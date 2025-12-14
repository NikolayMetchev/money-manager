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
import com.moneymanager.database.DbLocation
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.test.database.copyDatabaseFromResources
import com.moneymanager.test.database.createTestDatabaseManager
import com.moneymanager.test.database.deleteTestDatabase
import com.moneymanager.ui.MoneyManagerApp
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * End-to-end test for SchemaAwareLaunchedEffect.
 * Tests that when a schema error occurs in a coroutine launched with the schema-aware scope,
 * the error is caught and the DatabaseSchemaErrorDialog is displayed.
 *
 * Uses a test database with CSV import tables that have the old schema (with _row_id column
 * instead of row_index), which causes a schema error when CsvImportDetailScreen tries to
 * load the rows.
 */
@OptIn(ExperimentalTestApi::class)
class SchemaAwareLaunchedEffectE2ETest {
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
    fun schemaAwareLaunchedEffect_showsSchemaErrorDialog_whenCsvTableHasOldSchema() =
        runComposeUiTest {
            // Given: Copy the test database with old CSV schema to a test location
            testDbLocation = copyDatabaseFromResources("/money_manager_csv_old_schema.db")

            val databaseManager = createTestDatabaseManager()

            // When: MoneyManagerApp is initialized and we navigate to CSV imports
            setContent {
                MoneyManagerApp(
                    databaseManager =
                        TestDatabaseManager(
                            databaseManager = databaseManager,
                            testLocation = testDbLocation!!,
                        ),
                    appVersion = AppVersion("1.0.0-test"),
                )
            }

            // Wait for app to load
            waitForIdle()

            // Navigate to CSV imports screen
            onNodeWithText("CSV").performClick()
            waitForIdle()

            // Click on the first CSV import to open detail screen
            // The test database has a CSV import with this filename
            waitUntilExactlyOneExists(hasText("test_import", substring = true), timeoutMillis = 10000)
            onNodeWithText("test_import", substring = true).performClick()

            // Then: SchemaAwareLaunchedEffect should catch the schema error
            // and DatabaseSchemaErrorDialog should be displayed
            waitUntilExactlyOneExists(hasText("Database Schema Error"), timeoutMillis = 10000)
            onNodeWithText("Database Schema Error").assertIsDisplayed()
            onNodeWithText(text = "no such column", substring = true).assertIsDisplayed()
        }

    @Test
    fun schemaAwareLaunchedEffect_allowsRecovery_whenCsvTableHasOldSchema() =
        runComposeUiTest {
            // Given: Copy the test database with old CSV schema to a test location
            testDbLocation = copyDatabaseFromResources("/money_manager_csv_old_schema.db")

            val databaseManager = createTestDatabaseManager()

            setContent {
                MoneyManagerApp(
                    databaseManager =
                        TestDatabaseManager(
                            databaseManager = databaseManager,
                            testLocation = testDbLocation!!,
                        ),
                    appVersion = AppVersion("1.0.0-test"),
                )
            }

            // Wait for app to load and navigate to CSV detail
            waitForIdle()
            onNodeWithText("CSV").performClick()
            waitForIdle()
            waitUntilExactlyOneExists(hasText("test_import", substring = true), timeoutMillis = 10000)
            onNodeWithText("test_import", substring = true).performClick()

            // Wait for schema error dialog
            waitUntilExactlyOneExists(hasText("Database Schema Error"), timeoutMillis = 10000)

            // When: User clicks "Delete Database and Start Fresh"
            onNodeWithText("Delete Database and Start Fresh").performClick()

            // Then: Dialog should disappear and app should recover
            waitUntilDoesNotExist(hasText("Database Schema Error"), timeoutMillis = 10000)
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
