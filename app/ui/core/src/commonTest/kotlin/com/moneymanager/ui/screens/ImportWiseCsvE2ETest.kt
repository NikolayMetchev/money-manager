package com.moneymanager.ui.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import androidx.compose.ui.test.waitUntilExactlyOneExists
import com.moneymanager.database.DatabaseManager
import com.moneymanager.database.di.DatabaseComponent
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.test.database.createAccount
import com.moneymanager.test.database.createTestDatabaseLocation
import com.moneymanager.test.database.createTestDatabaseManager
import com.moneymanager.test.database.deleteTestDatabase
import com.moneymanager.test.database.installBuiltInCsvStrategies
import com.moneymanager.ui.test.MoneyManagerTestApp
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * End-to-end test for importing a Wise transaction-history CSV with the built-in
 * seeded "Wise CSV" strategy.
 *
 * The Wise export mixes currencies; the strategy resolves the per-row source account
 * from the currency column ("Wise: EUR", "Wise: GBP"), so no source account selection
 * is required in the Apply Strategy dialog.
 */
@OptIn(ExperimentalTestApi::class)
class ImportWiseCsvE2ETest {
    private var testDbLocation: DbLocation? = null

    @AfterTest
    fun cleanup() {
        testDbLocation?.let { location ->
            deleteTestDatabase(location)
        }
    }

    @Test
    fun importWiseCsv_appliesSeededStrategyWithoutSourceAccountSelection() =
        runMoneyManagerComposeUiTest {
            testDbLocation = createTestDatabaseLocation()
            val databaseManager = createTestDatabaseManager()

            runBlocking {
                val db = databaseManager.openDatabase(testDbLocation!!)
                val databaseComponent = DatabaseComponent.create(db)
                databaseComponent.installBuiltInCsvStrategies()

                // The Wise API import would normally have created the per-currency accounts
                for (name in listOf("Wise: EUR", "Wise: GBP")) {
                    databaseComponent.accountRepository.createAccount(
                        Account(id = AccountId(0), name = name, openingDate = Clock.System.now()),
                    )
                }

                val csvContent = wiseCsvContent()
                val lines = csvContent.lines().filter { it.isNotBlank() }
                val headers = parseCsvLine(lines.first())
                val rows = lines.drop(1).map { parseCsvLine(it) }

                databaseComponent.csvImportRepository.createImport(
                    fileName = "transaction-history.csv",
                    headers = headers,
                    rows = rows,
                    fileChecksum = "wise_test_export_checksum",
                    fileLastModified = Instant.fromEpochMilliseconds(1700000000000L),
                )
            }

            val testDatabaseManager =
                SimpleWiseDatabaseManager(
                    databaseManager = databaseManager,
                    testLocation = testDbLocation!!,
                )

            setContent {
                MoneyManagerTestApp(
                    databaseManager = testDatabaseManager,
                    appVersion = AppVersion("1.0.0-test"),
                )
            }

            waitForIdle()
            waitUntilAtLeastOneExists(hasText("Your Accounts"), timeoutMillis = 20000)

            // Navigate to the imported Wise CSV
            waitUntilAtLeastOneExists(hasText("Imports"), timeoutMillis = 10000)
            onNodeWithText("Imports", useUnmergedTree = true).performClick()
            waitForIdle()
            waitUntilAtLeastOneExists(hasText("CSV"), timeoutMillis = 10000)
            onNodeWithText("CSV", useUnmergedTree = true).performClick()
            waitForIdle()

            waitUntilExactlyOneExists(hasText("transaction-history.csv"), timeoutMillis = 15000)
            onNodeWithText("transaction-history.csv").performClick()
            waitUntilExactlyOneExists(hasText("5 rows"), timeoutMillis = 10000)

            // Apply the strategy - the seeded "Wise CSV" strategy auto-matches the header
            waitUntilAtLeastOneExists(hasText("Apply Strategy") and isEnabled(), timeoutMillis = 15000)
            onAllNodesWithText("Apply Strategy").onFirst().performClick()
            waitUntilAtLeastOneExists(hasText("Apply Import Strategy"), timeoutMillis = 15000)
            waitUntilExactlyOneExists(hasText("Wise CSV"), timeoutMillis = 10000)
            waitForIdle()

            // The strategy resolves the source account per-row, so no picker is shown
            onNodeWithText("Source Account").assertDoesNotExist()

            // Counterparties unknown to the database are offered as new accounts
            waitUntilAtLeastOneExists(hasText("New Account Handling"), timeoutMillis = 15000)
            waitUntilAtLeastOneExists(hasText("Avolta - Tenerife"), timeoutMillis = 10000)
            waitUntilAtLeastOneExists(hasText("TransferWise"), timeoutMillis = 10000)

            // All five rows import without a source account selection
            waitUntilAtLeastOneExists(hasText("Import 5 Transfers") and isEnabled(), timeoutMillis = 15000)
            onNodeWithText("Import 5 Transfers").performClick()
            waitUntilDoesNotExist(hasText("Apply Import Strategy"), timeoutMillis = 30000)
            waitForIdle()

            // Re-applying the strategy finds nothing left to import
            waitUntilAtLeastOneExists(hasText("Apply Strategy") and isEnabled(), timeoutMillis = 15000)
            onAllNodesWithText("Apply Strategy").onFirst().performClick()
            waitUntilAtLeastOneExists(hasText("Apply Import Strategy"), timeoutMillis = 15000)
            waitUntilAtLeastOneExists(
                hasText("All rows have already been imported successfully."),
                timeoutMillis = 15000,
            )
        }

    /**
     * Regression test for importing on a fresh database with no accounts at all: the dialog
     * must still prepare the import, offer the per-currency Wise accounts ("Wise: EUR",
     * "Wise: GBP") as new accounts to create, and enable the import button. Previously the
     * preview was gated on at least one account existing, so a fresh database showed
     * "Import 0 Transfers" with no way to proceed.
     */
    @Test
    fun importWiseCsv_onFreshDatabaseCreatesWiseAccounts() =
        runMoneyManagerComposeUiTest {
            testDbLocation = createTestDatabaseLocation()
            val databaseManager = createTestDatabaseManager()
            lateinit var databaseComponent: DatabaseComponent

            runBlocking {
                val db = databaseManager.openDatabase(testDbLocation!!)
                databaseComponent = DatabaseComponent.create(db)
                databaseComponent.installBuiltInCsvStrategies()

                // No accounts are created up front - the import must discover all of them
                val csvContent = wiseCsvContent()
                val lines = csvContent.lines().filter { it.isNotBlank() }
                val headers = parseCsvLine(lines.first())
                val rows = lines.drop(1).map { parseCsvLine(it) }

                databaseComponent.csvImportRepository.createImport(
                    fileName = "transaction-history.csv",
                    headers = headers,
                    rows = rows,
                    fileChecksum = "wise_test_export_checksum",
                    fileLastModified = Instant.fromEpochMilliseconds(1700000000000L),
                )
            }

            val testDatabaseManager =
                SimpleWiseDatabaseManager(
                    databaseManager = databaseManager,
                    testLocation = testDbLocation!!,
                )

            setContent {
                MoneyManagerTestApp(
                    databaseManager = testDatabaseManager,
                    appVersion = AppVersion("1.0.0-test"),
                )
            }

            waitForIdle()
            waitUntilAtLeastOneExists(hasText("Your Accounts"), timeoutMillis = 20000)

            // Navigate to the imported Wise CSV
            waitUntilAtLeastOneExists(hasText("Imports"), timeoutMillis = 10000)
            onNodeWithText("Imports", useUnmergedTree = true).performClick()
            waitForIdle()
            waitUntilAtLeastOneExists(hasText("CSV"), timeoutMillis = 10000)
            onNodeWithText("CSV", useUnmergedTree = true).performClick()
            waitForIdle()

            waitUntilExactlyOneExists(hasText("transaction-history.csv"), timeoutMillis = 15000)
            onNodeWithText("transaction-history.csv").performClick()
            waitUntilExactlyOneExists(hasText("5 rows"), timeoutMillis = 10000)

            waitUntilAtLeastOneExists(hasText("Apply Strategy") and isEnabled(), timeoutMillis = 15000)
            onAllNodesWithText("Apply Strategy").onFirst().performClick()
            waitUntilAtLeastOneExists(hasText("Apply Import Strategy"), timeoutMillis = 15000)
            waitUntilExactlyOneExists(hasText("Wise CSV"), timeoutMillis = 10000)
            waitForIdle()

            // The per-currency source accounts are offered as new accounts alongside counterparties
            waitUntilAtLeastOneExists(hasText("New Account Handling"), timeoutMillis = 15000)
            waitUntilAtLeastOneExists(hasText("Wise: EUR"), timeoutMillis = 10000)
            waitUntilAtLeastOneExists(hasText("Wise: GBP"), timeoutMillis = 10000)
            waitUntilAtLeastOneExists(hasText("Avolta - Tenerife"), timeoutMillis = 10000)

            // All five rows import even though no account existed beforehand
            waitUntilAtLeastOneExists(hasText("Import 5 Transfers") and isEnabled(), timeoutMillis = 15000)
            onNodeWithText("Import 5 Transfers").performClick()
            waitUntilDoesNotExist(hasText("Apply Import Strategy"), timeoutMillis = 30000)
            waitForIdle()

            // The per-currency Wise accounts were created during the import
            runBlocking {
                val accounts = databaseComponent.accountRepository.getAllAccounts().first()
                assertNotNull(accounts.singleOrNull { it.name == "Wise: EUR" }, "Wise: EUR account is created")
                assertNotNull(accounts.singleOrNull { it.name == "Wise: GBP" }, "Wise: GBP account is created")
            }
        }

    /**
     * A trimmed Wise transaction-history export covering the interesting row shapes:
     * an OUT card payment (EUR), an IN transfer (GBP), a balance conversion (EUR to GBP,
     * both sides the account holder), a fee row with an empty source name, and a
     * fully-refunded zero-amount card transaction.
     */
    private fun wiseCsvContent(): String =
        """
        ID,Status,Direction,Created on,Finished on,Source fee amount,Source fee currency,Target fee amount,Target fee currency,Source name,Source amount (after fees),Source currency,Target name,Target amount (after fees),Target currency,Exchange rate,Reference,Batch,Created by,Category,Note
        CARD_TRANSACTION-3695047474,COMPLETED,OUT,2026-04-20 11:39:52,2026-04-20 11:39:52,0.00,EUR,,,Nikolay Metchev,22.10,EUR,Avolta - Tenerife,22.10,EUR,1.0,,,Nikolay Metchev,Shopping,
        TRANSFER-1709494792,COMPLETED,IN,2026-03-10 09:00:00,2026-03-10 09:00:05,0.00,GBP,,,Ivan Metchev,2500.0,GBP,Nikolay Metchev,2500.0,GBP,1.0,Elit 3,,Ivan Metchev,General,
        BALANCE-100200300,COMPLETED,OUT,2026-02-01 12:00:00,2026-02-01 12:00:00,0.45,EUR,,,Nikolay Metchev,100.00,EUR,Nikolay Metchev,85.00,GBP,0.85,,,Nikolay Metchev,General,
        ACCRUAL_CHARGE-18326272,COMPLETED,OUT,2026-06-02 15:12:22,2026-06-02 15:12:22,,,,,,0.06,EUR,TransferWise,0.06,EUR,1.00000000,Assets fee 18326272,,,Bills,
        CARD_TRANSACTION-3064849936,REFUNDED,OUT,2026-01-15 10:30:00,2026-01-15 10:30:00,0.00,EUR,,,Nikolay Metchev,0.00,EUR,Atac Roma - tap & go,0.00,EUR,1,,,Nikolay Metchev,Transport,
        """.trimIndent()

    /**
     * Simple CSV line parser that handles quoted fields with commas.
     */
    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        var current = StringBuilder()
        var inQuotes = false

        for (char in line) {
            when (char) {
                '"' -> inQuotes = !inQuotes
                ',' if !inQuotes -> {
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
private class SimpleWiseDatabaseManager(
    private val databaseManager: DatabaseManager,
    private val testLocation: DbLocation,
) : DatabaseManager by databaseManager {
    override fun getDefaultLocation(): DbLocation = testLocation
}
