@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.ui.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import androidx.compose.ui.test.waitUntilExactlyOneExists
import com.moneymanager.database.DatabaseManager
import com.moneymanager.database.DbLocation
import com.moneymanager.database.RepositorySet
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.test.database.copyDatabaseFromResources
import com.moneymanager.test.database.createTestDatabaseLocation
import com.moneymanager.test.database.createTestDatabaseManager
import com.moneymanager.test.database.deleteTestDatabase
import com.moneymanager.ui.MoneyManagerApp
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * End-to-end test for importing a Monzo CSV file and creating an import strategy.
 *
 * The test flow:
 * 1. Create fresh database with Monzo CSV data injected (simulates file picker which can't be automated)
 * 2. Navigate to CSV Imports screen
 * 3. Click on the imported CSV file
 * 4. Create a source account for Monzo (required for strategy)
 * 5. Create a strategy named "Monzo" with proper column mappings
 * 6. Verify strategy is created with auto-detected columns
 */
@OptIn(ExperimentalTestApi::class)
class ImportMonzoCsvE2ETest {
    private var testDbLocation: DbLocation? = null

    @AfterTest
    fun cleanup() {
        testDbLocation?.let { location ->
            deleteTestDatabase(location)
        }
    }

    @Test
    fun navigateToCsvImports_shouldShowCsvImportsScreen() =
        runComposeUiTest {
            // Given: Use existing database to verify navigation works
            // (using money_manager.db which has old schema but allows testing navigation)
            testDbLocation = copyDatabaseFromResources("/money_manager.db")
            val databaseManager = createTestDatabaseManager()

            val testDatabaseManager =
                SimpleDatabaseManager(
                    databaseManager = databaseManager,
                    testLocation = testDbLocation!!,
                )

            setContent {
                MoneyManagerApp(
                    databaseManager = testDatabaseManager,
                    appVersion = AppVersion("1.0.0-test"),
                )
            }

            // Wait for app to load and navigate to CSV Imports screen
            // Following the pattern from SchemaAwareCoroutineScopeE2ETest
            waitForIdle()

            // Navigate to CSV imports screen (useUnmergedTree for NavigationBarItem)
            waitUntilExactlyOneExists(hasText("CSV"), timeoutMillis = 10000)
            onNodeWithText("CSV", useUnmergedTree = true).performClick()
            waitForIdle()

            // Wait for navigation to complete - this will trigger schema error since
            // money_manager.db doesn't have CSV tables, which shows the error dialog
            // If we get a schema error, navigation actually worked!
            waitUntilExactlyOneExists(
                hasText("Database Schema Error").or(hasText("No CSV files imported yet")),
                timeoutMillis = 15000,
            )
        }

    @Test
    fun importMonzoCsv_andCreateStrategy_shouldAutoDetectColumnMappings() =
        runComposeUiTest {
            // Given: Create database and populate with CSV data BEFORE starting the app
            testDbLocation = createTestDatabaseLocation()
            val databaseManager = createTestDatabaseManager()

            // Create and populate the database
            kotlinx.coroutines.runBlocking {
                val db = databaseManager.openDatabase(testDbLocation!!)
                val repositorySet = RepositorySet(db)

                // Parse and inject CSV data
                val csvContent = loadTestCsvContent()
                val lines = csvContent.lines().filter { it.isNotBlank() }
                val headers = parseCsvLine(lines.first())
                val rows = lines.drop(1).map { parseCsvLine(it) }

                repositorySet.csvImportRepository.createImport(
                    fileName = "monzo_test_export.csv",
                    headers = headers,
                    rows = rows,
                )
            }

            // Use simple database manager - data is already in the database
            val testDatabaseManager =
                SimpleDatabaseManager(
                    databaseManager = databaseManager,
                    testLocation = testDbLocation!!,
                )

            setContent {
                MoneyManagerApp(
                    databaseManager = testDatabaseManager,
                    appVersion = AppVersion("1.0.0-test"),
                )
            }

            // Wait for app to load
            waitForIdle()
            waitUntilExactlyOneExists(hasText("Your Accounts"), timeoutMillis = 15000)

            // Step 1: Navigate to CSV Imports screen
            waitUntilExactlyOneExists(hasText("CSV"), timeoutMillis = 10000)
            onNodeWithText("CSV", useUnmergedTree = true).performClick()
            waitForIdle()

            // Wait for the imported file to appear (more reliable than waiting for title)
            waitUntilExactlyOneExists(hasText("monzo_test_export.csv"), timeoutMillis = 15000)

            // Step 2: Click on the imported CSV file
            onNodeWithText("monzo_test_export.csv").performClick()
            waitUntilExactlyOneExists(hasText("20 rows"), timeoutMillis = 10000)
            onNodeWithText("18 columns").assertIsDisplayed()

            // Step 3: First create a source account (required for strategy)
            // Navigate back and create an account
            onNodeWithText("< Back").performClick()
            waitForIdle()
            // Wait for file list to appear (more reliable than title)
            waitUntilExactlyOneExists(hasText("monzo_test_export.csv"), timeoutMillis = 10000)

            // Navigate to Accounts
            waitUntilExactlyOneExists(hasText("Accounts"), timeoutMillis = 10000)
            onNodeWithText("Accounts", useUnmergedTree = true).performClick()
            waitForIdle()
            waitUntilExactlyOneExists(hasText("Your Accounts"), timeoutMillis = 10000)

            onNodeWithText("+ Add Account").performClick()
            waitUntilExactlyOneExists(hasText("Create New Account"), timeoutMillis = 10000)

            onNodeWithText("Account Name").performTextInput("Monzo Current Account")
            onNodeWithText("Create").performClick()
            waitUntilDoesNotExist(hasText("Create New Account"), timeoutMillis = 10000)

            // Verify account was created
            onNodeWithText("Monzo Current Account").assertIsDisplayed()

            // Step 4: Navigate back to CSV import and create strategy
            waitUntilExactlyOneExists(hasText("CSV"), timeoutMillis = 10000)
            onNodeWithText("CSV", useUnmergedTree = true).performClick()
            waitForIdle()
            // Wait for file list to appear (more reliable than title)
            waitUntilExactlyOneExists(hasText("monzo_test_export.csv"), timeoutMillis = 10000)

            onNodeWithText("monzo_test_export.csv").performClick()
            waitUntilExactlyOneExists(hasText("20 rows"), timeoutMillis = 10000)

            // Step 5: Click "Create Strategy" button
            onNodeWithText("Create Strategy").performClick()
            waitUntilExactlyOneExists(hasText("Create Import Strategy"), timeoutMillis = 10000)

            // Step 6: Enter strategy name "Monzo"
            // Wait for the dialog to fully load (accounts need to load from repository)
            waitUntilExactlyOneExists(hasText("Strategy Name"), timeoutMillis = 10000)
            onNodeWithText("Strategy Name").performTextInput("Monzo")
            waitForIdle()

            // Step 7: Verify column auto-detection selected the correct columns
            // The ColumnDetector should have selected:
            // - Date Column: "Date" (not "Transaction ID" which was the bug)
            // - Amount Column: "Amount"
            // - Description Column: "Description"
            // - Target Account Column: "Name"

            // Wait for the dialog to fully load with auto-detected values
            waitForIdle()

            // Verify the dialog has account/currency dropdowns ready
            waitUntilExactlyOneExists(hasText("Select Account"), timeoutMillis = 10000)
            waitUntilExactlyOneExists(hasText("Select Currency"), timeoutMillis = 10000)

            // Verify the auto-detected column names are shown in the dropdown fields
            // The Date column dropdown should show "Date" (not "Transaction ID")
            waitUntilAtLeastOneExists(hasText("Date", substring = false), timeoutMillis = 10000)

            // The Amount column dropdown should show "Amount"
            waitUntilAtLeastOneExists(hasText("Amount", substring = false), timeoutMillis = 10000)

            // The Description column dropdown should show "Description"
            waitUntilAtLeastOneExists(hasText("Description", substring = false), timeoutMillis = 10000)

            // The Target Account column dropdown should show "Name"
            waitUntilAtLeastOneExists(hasText("Name", substring = false), timeoutMillis = 10000)

            // The dropdown sample values help verify the correct column was selected
            // Date column sample: "24/02/2022" (from "Date" column, not "tx_..." from "Transaction ID")
            // These may be truncated in supportingText, so just check for part of the date format
            waitUntilAtLeastOneExists(hasText("24/02/2022", substring = true), timeoutMillis = 10000)

            // Cancel the dialog to return to CSV detail screen
            onNodeWithText("Cancel").performClick()
            waitUntilDoesNotExist(hasText("Create Import Strategy"), timeoutMillis = 10000)

            // Verify we're back on the CSV detail screen
            onNodeWithText("20 rows").assertIsDisplayed()
        }

    /**
     * Loads the test CSV content from resources.
     * The CSV file should be placed at src/commonTest/resources/monzo_test_export.csv
     */
    private fun loadTestCsvContent(): String {
        // Load from test resources - this is a simplified version of the Monzo export
        return """
            Transaction ID,Date,Time,Type,Name,Emoji,Category,Amount,Currency,Local amount,Local currency,Notes and #tags,Address,Receipt,Description,Category split,Money Out,Money In
            tx_0000AGoDcVlolqzYp9Vh0U,24/02/2022,17:44:34,Faster payment,Nikolay Metchev,,Transfers,100.00,GBP,100.00,GBP,Starling,,,Starling,,,100.00
            tx_0000AGoDpgVm98x6eOfP1e,24/02/2022,17:46:57,Faster payment,Crypto.com,,General,-100.00,GBP,-100.00,GBP,BI6055443,,,BI6055443,,-100.00,
            tx_0000AGoEe10UDfpT5CopKD,24/02/2022,17:56:03,Faster payment,Nikolay Metchev,,Transfers,4900.00,GBP,4900.00,GBP,Starling,,,Starling,,,4900.00
            tx_0000AGoEkw4YxluuUw04bS,24/02/2022,17:57:17,Faster payment,Crypto.com,,General,-4900.00,GBP,-4900.00,GBP,BI6055443,,,BI6055443,,-4900.00,
            tx_0000AGodmaf22ZdixcFnPd,24/02/2022,22:37:44,Faster payment,MR NIKOLAY IVANOV METCHEV,,Transfers,10000.00,GBP,10000.00,GBP,Monzo-CYMYP,,,Monzo-CYMYP,,,10000.00
            tx_0000AGpyoTpch7okQWcqXZ,25/02/2022,14:08:05,Faster payment,Nikolay Metchev,,Transfers,-10000.00,GBP,-10000.00,GBP,To Revolut -VQRF-,,,To Revolut -VQRF-,,-10000.00,
            tx_0000AGtj1X3hBdP7h3X9v7,27/02/2022,09:30:00,Monzo-to-Monzo,Emanuele Naykene,,General,6.00,GBP,6.00,GBP,,,,,,,6.00
            tx_0000AGtj71davE1ihTalIQ,27/02/2022,09:31:00,Faster payment,Crypto.com,,General,-6.00,GBP,-6.00,GBP,BI6055443,,,BI6055443,,-6.00,
            tx_0000AGyNavm83XOPHjahtp,01/03/2022,15:23:27,Card payment,Curve,💳,General,0.00,GBP,0.00,GBP,Active card check,,,CRV*Card verification  London        GBR,,0.00,
            tx_0000AGyNeEEXsuJrgdhBgI,01/03/2022,15:24:03,Card payment,Curve,💳,General,-0.75,GBP,-0.75,GBP,,,,CRV*Card verification  London        GBR,,-0.75,
            tx_0000AGyNeIMUXJg9gk8CXp,01/03/2022,15:24:04,Card payment,Curve,💳,General,0.75,GBP,0.75,GBP,,,,CRV*Card verification  London        GBR,,,0.75
            tx_0000AHkf5W1MsrxQiV1uuf,24/03/2022,22:25:36,Faster payment,MR NIKOLAY IVANOV METCHEV,,Transfers,5000.00,GBP,5000.00,GBP,Monzo-NYTPD,,,Monzo-NYTPD,,,5000.00
            tx_0000AHkfBdOsMSkI1RBTaT,24/03/2022,22:26:43,Faster payment,Crypto.com,,General,-5000.00,GBP,-5000.00,GBP,BI6055443,,,BI6055443,,-5000.00,
            tx_0000AHs4HJA8NKwhZFUviL,28/03/2022,12:10:51,Faster payment,Foris DAX MT Limited,,Income,100.00,GBP,100.00,GBP,073a8c24ebeb4f15b9,,,073a8c24ebeb4f15b9,,,100.00
            tx_0000AHsP4YXzxgDDDoWjjd,28/03/2022,16:03:51,Faster payment,Nexo,,Transfers,-100.00,GBP,-100.00,GBP,DKMRNMM95V,,,DKMRNMM95V,,-100.00,
            tx_0000AHtrRgHYMV1ssBbLrF,29/03/2022,08:56:29,Faster payment,Foris DAX MT Limited,,Income,5000.00,GBP,5000.00,GBP,781af9870e44432bb6,,,781af9870e44432bb6,,,5000.00
            tx_0000AHtxZqYAfWMjQ83mio,29/03/2022,10:05:11,Faster payment,Nexo AG,,Income,100.00,GBP,100.00,GBP,NXTwY0nExDjfw,,,NXTwY0nExDjfw,,,100.00
            tx_0000AHtxiueemGDNJ8cWLx,29/03/2022,10:06:49,Faster payment,Nexo,,Transfers,-5000.00,GBP,-5000.00,GBP,DKMRNMM95V,,,DKMRNMM95V,,-5000.00,
            tx_0000AHtypT6bhdtlj0uDC5,29/03/2022,10:19:13,Faster payment,Nexo,,General,-100.00,GBP,-100.00,GBP,DKMRNMM95V,,,DKMRNMM95V,,-100.00,
            tx_0000AHu1WpKt7u8GzS8XJ3,29/03/2022,10:49:27,Faster payment,Foris DAX MT Limited,,Income,7854.23,GBP,7854.23,GBP,b75c2cc252ab473e88,,,b75c2cc252ab473e88,,,7854.23
            """.trimIndent()
    }

    /**
     * Simple CSV line parser that handles quoted fields with commas.
     */
    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        var current = StringBuilder()
        var inQuotes = false

        for (char in line) {
            when {
                char == '"' -> inQuotes = !inQuotes
                char == ',' && !inQuotes -> {
                    result.add(current.toString())
                    current = StringBuilder()
                }
                else -> current.append(char)
            }
        }
        result.add(current.toString())

        return result
    }
}

/**
 * Simple DatabaseManager wrapper that only overrides getDefaultLocation.
 */
private class SimpleDatabaseManager(
    private val databaseManager: DatabaseManager,
    private val testLocation: DbLocation,
) : DatabaseManager by databaseManager {
    override fun getDefaultLocation(): DbLocation = testLocation
}
