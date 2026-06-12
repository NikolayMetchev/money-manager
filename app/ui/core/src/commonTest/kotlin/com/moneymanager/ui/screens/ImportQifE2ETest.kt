@file:OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)

package com.moneymanager.ui.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
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
import com.moneymanager.database.qif.QifCsvAdapter
import com.moneymanager.di.database.DatabaseComponent
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.FieldMappingId
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedCurrencyMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedTimezoneMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.qif.QifParser
import com.moneymanager.test.database.createTestDatabaseLocation
import com.moneymanager.test.database.createTestDatabaseManager
import com.moneymanager.test.database.deleteTestDatabase
import com.moneymanager.ui.screens.qif.dominantAccountType
import com.moneymanager.ui.screens.qif.toImportRecords
import com.moneymanager.ui.test.MoneyManagerTestApp
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * End-to-end test for the QIF import flow. A fresh database seeds the built-in Wise/Monzo CSV
 * strategies (which reference CSV columns like "Name"), so this also guards against the apply dialog
 * auto-selecting an incompatible strategy for a QIF file.
 */
@OptIn(ExperimentalTestApi::class)
class ImportQifE2ETest {
    private var testDbLocation: DbLocation? = null

    @AfterTest
    fun cleanup() {
        testDbLocation?.let { deleteTestDatabase(it) }
    }

    private val qifContent =
        """
        !Type:Bank
        D04/05/2020
        T-84.20
        PMarcus Transfer
        ^
        D04/05/2020
        T120.00
        PSalary
        ^
        """.trimIndent()

    private fun qifStrategy(
        sourceId: AccountId,
        targetId: AccountId,
        currencyId: com.moneymanager.domain.model.CurrencyId,
    ): CsvImportStrategy =
        CsvImportStrategy(
            id = CsvImportStrategyId(Uuid.random()),
            // Sorts before the seeded "QIF" strategy so it is the one auto-selected.
            name = "AAA QIF Test",
            identificationColumns = QifCsvAdapter.headers.toSet(),
            fieldMappings =
                mapOf(
                    TransferField.SOURCE_ACCOUNT to
                        HardCodedAccountMapping(FieldMappingId(Uuid.random()), TransferField.SOURCE_ACCOUNT, sourceId),
                    TransferField.TARGET_ACCOUNT to
                        HardCodedAccountMapping(FieldMappingId(Uuid.random()), TransferField.TARGET_ACCOUNT, targetId),
                    TransferField.TIMESTAMP to
                        DateTimeParsingMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.TIMESTAMP,
                            dateColumnName = QifCsvAdapter.COL_DATE,
                            dateFormat = "dd/MM/yyyy",
                        ),
                    TransferField.DESCRIPTION to
                        DirectColumnMapping(FieldMappingId(Uuid.random()), TransferField.DESCRIPTION, QifCsvAdapter.COL_PAYEE),
                    TransferField.AMOUNT to
                        AmountParsingMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.AMOUNT,
                            mode = AmountMode.SINGLE_COLUMN,
                            amountColumnName = QifCsvAdapter.COL_AMOUNT,
                        ),
                    TransferField.CURRENCY to
                        HardCodedCurrencyMapping(FieldMappingId(Uuid.random()), TransferField.CURRENCY, currencyId),
                    TransferField.TIMEZONE to
                        HardCodedTimezoneMapping(FieldMappingId(Uuid.random()), TransferField.TIMEZONE, "UTC"),
                ),
            createdAt = Instant.fromEpochMilliseconds(1700000000000L),
            updatedAt = Instant.fromEpochMilliseconds(1700000000000L),
        )

    private suspend fun injectQifImport(
        component: DatabaseComponent,
        fileName: String = "statement.qif",
        checksum: String = "qif_e2e_checksum",
    ) {
        val parsed = QifParser().parse(qifContent)
        component.qifImportRepository.createImport(
            fileName = fileName,
            records = parsed.toImportRecords(),
            accountType = parsed.dominantAccountType(),
            fileChecksum = checksum,
            fileLastModified = Instant.fromEpochMilliseconds(1700000000000L),
        )
    }

    @Test
    fun applyQifStrategy_usesDefaultCurrencyAndImportsTransfers() =
        runMoneyManagerComposeUiTest {
            testDbLocation = createTestDatabaseLocation()
            val databaseManager = createTestDatabaseManager()

            runBlocking {
                val db = databaseManager.openDatabase(testDbLocation!!)
                val component = DatabaseComponent.create(db)

                // Default currency = USD; the apply dialog should pre-select it.
                val usdId = component.currencyRepository.upsertCurrencyByCode("USD", "US Dollar")
                component.settingsRepository.setDefaultCurrencyId(usdId)

                component.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Cash Account", openingDate = Instant.fromEpochMilliseconds(1700000000000L)),
                )
                component.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Expenses", openingDate = Instant.fromEpochMilliseconds(1700000000000L)),
                )
                val accounts = component.accountRepository.getAllAccounts().first()
                val source = accounts.first { it.name == "Cash Account" }
                val target = accounts.first { it.name == "Expenses" }

                component.csvImportStrategyRepository.createStrategy(qifStrategy(source.id, target.id, usdId))
                injectQifImport(component)
            }

            setContent {
                MoneyManagerTestApp(
                    databaseManager = QifTestDatabaseManager(databaseManager, testDbLocation!!),
                    appVersion = AppVersion("1.0.0-test"),
                )
            }

            waitForIdle()
            waitUntilAtLeastOneExists(hasText("Your Accounts"), timeoutMillis = 20000)

            // Imports -> QIF tab -> open the import
            onNodeWithText("Imports", useUnmergedTree = true).performClick()
            waitForIdle()
            onNodeWithText("QIF").performClick()
            waitUntilExactlyOneExists(hasText("statement.qif"), timeoutMillis = 15000)
            onNodeWithText("statement.qif").performClick()

            // Apply Strategy dialog
            waitUntilAtLeastOneExists(hasText("Apply Strategy") and isEnabled(), timeoutMillis = 15000)
            onAllNodesWithText("Apply Strategy").onFirst().performClick()
            waitUntilExactlyOneExists(hasText("Apply Import Strategy"), timeoutMillis = 15000)

            // The matching QIF strategy is auto-selected (NOT a seeded CSV strategy).
            waitUntilAtLeastOneExists(hasText("AAA QIF Test"), timeoutMillis = 10000)
            // Currency defaults to the settings default currency.
            waitUntilAtLeastOneExists(hasText("USD - US Dollar", substring = true), timeoutMillis = 10000)

            // The import button ("Import N Transfers") becomes enabled — preview succeeded, i.e. no
            // "Column not found" error. "Transfers" is unique to the button (the title is not).
            waitUntilAtLeastOneExists(hasText("Transfers", substring = true) and isEnabled(), timeoutMillis = 15000)
            onNodeWithText("Transfers", substring = true).performClick()

            // Dialog closes and the records become Imported.
            waitUntilDoesNotExist(hasText("Apply Import Strategy"), timeoutMillis = 20000)
            waitForIdle()
            waitUntilAtLeastOneExists(hasText("Imported"), timeoutMillis = 15000)
            // Everything is imported, so the Apply Strategy button is no longer enabled.
            onNodeWithText("Apply Strategy").assertIsNotEnabled()
        }

    @Test
    fun builtInQifStrategy_alwaysMatchesOutOfTheBox() =
        runMoneyManagerComposeUiTest {
            testDbLocation = createTestDatabaseLocation()
            val databaseManager = createTestDatabaseManager()

            runBlocking {
                val db = databaseManager.openDatabase(testDbLocation!!)
                val component = DatabaseComponent.create(db)
                val usdId = component.currencyRepository.upsertCurrencyByCode("USD", "US Dollar")
                component.settingsRepository.setDefaultCurrencyId(usdId)
                // No user strategy: rely entirely on the seeded built-in "QIF" strategy.
                injectQifImport(component)
            }

            setContent {
                MoneyManagerTestApp(
                    databaseManager = QifTestDatabaseManager(databaseManager, testDbLocation!!),
                    appVersion = AppVersion("1.0.0-test"),
                )
            }

            waitForIdle()
            waitUntilAtLeastOneExists(hasText("Your Accounts"), timeoutMillis = 20000)

            onNodeWithText("Imports", useUnmergedTree = true).performClick()
            waitForIdle()
            onNodeWithText("QIF").performClick()
            waitUntilExactlyOneExists(hasText("statement.qif"), timeoutMillis = 15000)
            onNodeWithText("statement.qif").performClick()

            // The detail screen has a back button.
            waitUntilAtLeastOneExists(hasText("< Back"), timeoutMillis = 15000)

            waitUntilAtLeastOneExists(hasText("Apply Strategy") and isEnabled(), timeoutMillis = 15000)
            onAllNodesWithText("Apply Strategy").onFirst().performClick()
            waitUntilExactlyOneExists(hasText("Apply Import Strategy"), timeoutMillis = 15000)

            // The built-in "QIF" strategy is auto-selected, so the dialog is ready to import (the
            // currency defaults to the settings default) and the "no matching strategy" hint is absent.
            waitUntilAtLeastOneExists(hasText("USD - US Dollar", substring = true), timeoutMillis = 10000)
            waitForIdle()
            onNodeWithText("No QIF strategy matches this file yet", substring = true).assertDoesNotExist()
        }

    @Test
    fun fileWithAllDuplicateTransactions_stillShowsAsImported() =
        runMoneyManagerComposeUiTest {
            testDbLocation = createTestDatabaseLocation()
            val databaseManager = createTestDatabaseManager()

            runBlocking {
                val db = databaseManager.openDatabase(testDbLocation!!)
                val component = DatabaseComponent.create(db)
                val usdId = component.currencyRepository.upsertCurrencyByCode("USD", "US Dollar")
                component.settingsRepository.setDefaultCurrencyId(usdId)
                component.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Cash Account", openingDate = Instant.fromEpochMilliseconds(1700000000000L)),
                )
                component.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Expenses", openingDate = Instant.fromEpochMilliseconds(1700000000000L)),
                )
                val accounts = component.accountRepository.getAllAccounts().first()
                val source = accounts.first { it.name == "Cash Account" }
                val target = accounts.first { it.name == "Expenses" }
                component.csvImportStrategyRepository.createStrategy(qifStrategy(source.id, target.id, usdId))
                // Two files with identical transactions (different checksums): the second is all duplicates.
                injectQifImport(component, fileName = "first.qif", checksum = "c1")
                injectQifImport(component, fileName = "second.qif", checksum = "c2")
            }

            setContent {
                MoneyManagerTestApp(
                    databaseManager = QifTestDatabaseManager(databaseManager, testDbLocation!!),
                    appVersion = AppVersion("1.0.0-test"),
                )
            }

            waitForIdle()
            waitUntilAtLeastOneExists(hasText("Your Accounts"), timeoutMillis = 20000)
            onNodeWithText("Imports", useUnmergedTree = true).performClick()
            waitForIdle()
            onNodeWithText("QIF").performClick()
            waitUntilExactlyOneExists(hasText("first.qif"), timeoutMillis = 15000)

            // Apply to the first file (creates the transfers), then to the second (all duplicates).
            for (file in listOf("first.qif", "second.qif")) {
                // After navigating back from the previous file the list re-renders, so wait for the
                // row before clicking it (it may not be present on the first recomposition on CI).
                waitUntilAtLeastOneExists(hasText(file), timeoutMillis = 15000)
                onAllNodesWithText(file).onFirst().performClick()
                waitUntilAtLeastOneExists(hasText("Apply Strategy") and isEnabled(), timeoutMillis = 15000)
                onAllNodesWithText("Apply Strategy").onFirst().performClick()
                waitUntilExactlyOneExists(hasText("Apply Import Strategy"), timeoutMillis = 15000)
                waitUntilAtLeastOneExists(hasText("Transfers", substring = true) and isEnabled(), timeoutMillis = 15000)
                onNodeWithText("Transfers", substring = true).performClick()
                waitUntilDoesNotExist(hasText("Apply Import Strategy"), timeoutMillis = 20000)
                waitForIdle()
                onNodeWithText("< Back").performClick()
                waitForIdle()
            }

            // Both files have moved to the Imported tab — including the all-duplicate second file —
            // and the Unimported tab is now empty. Wait for the tab count to reflect both imports:
            // the count is driven by an async repository flow, so waitForIdle alone may race it.
            waitUntilExactlyOneExists(hasText("Imported (2)"), timeoutMillis = 15000)
            onNodeWithText("Imported (2)").performClick()
            waitForIdle()
            waitUntilExactlyOneExists(hasText("second.qif"), timeoutMillis = 15000)
            onNodeWithText("Unimported (0)").assertExists()
        }

    @Test
    fun importAll_appliesStrategyToEveryUnimportedFileAtOnce() =
        runMoneyManagerComposeUiTest {
            testDbLocation = createTestDatabaseLocation()
            val databaseManager = createTestDatabaseManager()

            runBlocking {
                val db = databaseManager.openDatabase(testDbLocation!!)
                val component = DatabaseComponent.create(db)
                val usdId = component.currencyRepository.upsertCurrencyByCode("USD", "US Dollar")
                component.settingsRepository.setDefaultCurrencyId(usdId)
                component.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Cash Account", openingDate = Instant.fromEpochMilliseconds(1700000000000L)),
                )
                component.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Expenses", openingDate = Instant.fromEpochMilliseconds(1700000000000L)),
                )
                val accounts = component.accountRepository.getAllAccounts().first()
                val source = accounts.first { it.name == "Cash Account" }
                val target = accounts.first { it.name == "Expenses" }
                component.csvImportStrategyRepository.createStrategy(qifStrategy(source.id, target.id, usdId))
                // Remember the source account so the bulk dialog pre-fills it (no manual selection).
                component.settingsRepository.setLastQifAccountId(source.id)
                injectQifImport(component, fileName = "first.qif", checksum = "c1")
                injectQifImport(component, fileName = "second.qif", checksum = "c2")
            }

            setContent {
                MoneyManagerTestApp(
                    databaseManager = QifTestDatabaseManager(databaseManager, testDbLocation!!),
                    appVersion = AppVersion("1.0.0-test"),
                )
            }

            waitForIdle()
            waitUntilAtLeastOneExists(hasText("Your Accounts"), timeoutMillis = 20000)
            onNodeWithText("Imports", useUnmergedTree = true).performClick()
            waitForIdle()
            onNodeWithText("QIF").performClick()

            // The Unimported tab shows a single "Import all (2)" action.
            waitUntilExactlyOneExists(hasText("Import all (2)"), timeoutMillis = 15000)
            onNodeWithText("Import all (2)").performClick()

            // The dialog opens with the source account pre-filled; confirm.
            waitUntilExactlyOneExists(hasText("Import all unimported files"), timeoutMillis = 15000)
            waitUntilAtLeastOneExists(hasText("Import 2 files") and isEnabled(), timeoutMillis = 15000)
            onNodeWithText("Import 2 files").performClick()

            // Summary shown, then dismiss.
            waitUntilAtLeastOneExists(hasText("Imported 2 files", substring = true), timeoutMillis = 20000)
            onNodeWithText("Done").performClick()
            waitForIdle()

            // Both files are now imported in one click. Wait for the async count flow to catch up.
            waitUntilExactlyOneExists(hasText("Imported (2)"), timeoutMillis = 15000)
            onNodeWithText("Imported (2)").performClick()
            waitForIdle()
            waitUntilExactlyOneExists(hasText("second.qif"), timeoutMillis = 15000)
            onNodeWithText("Unimported (0)").assertExists()
        }
}

/** DatabaseManager wrapper that points the app at the pre-populated test database. */
private class QifTestDatabaseManager(
    databaseManager: DatabaseManager,
    private val testLocation: DbLocation,
) : DatabaseManager by databaseManager {
    override fun getDefaultLocation(): DbLocation = testLocation
}
