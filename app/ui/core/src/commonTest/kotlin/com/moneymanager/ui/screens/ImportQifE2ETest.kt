package com.moneymanager.ui.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import androidx.compose.ui.test.waitUntilExactlyOneExists
import com.moneymanager.csvimporter.parseQifAsCsv
import com.moneymanager.database.DatabaseManager
import com.moneymanager.database.di.DatabaseComponent
import com.moneymanager.database.di.openDatabaseComponent
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedCurrencyMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedTimezoneMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.qif.QifColumns
import com.moneymanager.domain.model.rules.ValueExpr
import com.moneymanager.test.database.createAccount
import com.moneymanager.test.database.createTestDatabaseLocation
import com.moneymanager.test.database.createTestDatabaseManager
import com.moneymanager.test.database.deleteTestDatabase
import com.moneymanager.test.database.installBuiltInCsvStrategies
import com.moneymanager.test.database.upsertCurrencyByCode
import com.moneymanager.ui.test.MoneyManagerTestApp
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * End-to-end test for the QIF import flow: a QIF file is staged as a CSV import of its rows and listed on the
 * CSV tab. The built-in strategies (CSV ones referencing columns like "Name", and the bank QIF ones with
 * content rules) are installed too, so this also guards against the apply dialog auto-selecting one of them
 * for a QIF file they don't recognise.
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
            name = "AAA QIF Test",
            config =
                CsvStrategyConfig(
                    identificationColumns = QifColumns.headers.toSet(),
                    fieldMappings =
                        mapOf(
                            TransferField.SOURCE_ACCOUNT to
                                HardCodedAccountMapping(TransferField.SOURCE_ACCOUNT, sourceId),
                            TransferField.TARGET_ACCOUNT to
                                HardCodedAccountMapping(TransferField.TARGET_ACCOUNT, targetId),
                            TransferField.TIMESTAMP to
                                DateTimeParsingMapping(
                                    fieldType = TransferField.TIMESTAMP,
                                    dateColumnName = QifColumns.COL_DATE,
                                    dateFormat = "dd/MM/yyyy",
                                ),
                            TransferField.DESCRIPTION to
                                DirectColumnMapping(fieldType = TransferField.DESCRIPTION, value = ValueExpr(listOf(QifColumns.COL_PAYEE))),
                            TransferField.AMOUNT to
                                AmountParsingMapping(
                                    fieldType = TransferField.AMOUNT,
                                    mode = AmountMode.SINGLE_COLUMN,
                                    amountColumnName = QifColumns.COL_AMOUNT,
                                ),
                            TransferField.CURRENCY to
                                HardCodedCurrencyMapping(TransferField.CURRENCY, currencyId),
                            TransferField.TIMEZONE to
                                HardCodedTimezoneMapping(TransferField.TIMEZONE, "UTC"),
                        ),
                ),
            createdAt = Instant.fromEpochMilliseconds(1700000000000L),
            updatedAt = Instant.fromEpochMilliseconds(1700000000000L),
        )

    private suspend fun injectQifImport(
        component: DatabaseComponent,
        fileName: String = "statement.qif",
        checksum: String = "qif_e2e_checksum",
    ) {
        // What stageQifImport writes; the component has no import engine of its own to call it on.
        val parsed = parseQifAsCsv(qifContent)
        component.csvImportRepository.createImport(
            fileName = fileName,
            headers = parsed.headers,
            rows = parsed.rows,
            fileChecksum = checksum,
            fileLastModified = Instant.fromEpochMilliseconds(1700000000000L),
        )
    }

    @Test
    fun applyQifStrategy_autoSelectsItAndImportsTransfers() {
        runMoneyManagerComposeUiTest {
            testDbLocation = createTestDatabaseLocation()
            val databaseManager = createTestDatabaseManager()

            runBlocking {
                val db = databaseManager.openDatabase(testDbLocation!!)
                val component = openDatabaseComponent(db)
                component.installBuiltInCsvStrategies()

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

                component.csvImportStrategyRepository.createStrategy(qifStrategy(source.id, target.id, usdId), Source.Manual)
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

            // Imports -> CSV tab -> open the import
            onNodeWithText("Imports", useUnmergedTree = true).performClick()
            waitForIdle()
            onNodeWithText("CSV", useUnmergedTree = true).performClick()
            waitUntilExactlyOneExists(hasText("statement.qif"), timeoutMillis = 15000)
            onNodeWithText("statement.qif").performClick()

            // Apply Strategy dialog
            waitUntilAtLeastOneExists(hasText("Apply Strategy") and isEnabled(), timeoutMillis = 15000)
            onAllNodesWithText("Apply Strategy").onFirst().performClick()
            waitUntilExactlyOneExists(hasText("Apply Import Strategy"), timeoutMillis = 15000)

            // The matching QIF strategy is auto-selected (NOT a built-in strategy).
            waitUntilAtLeastOneExists(hasText("AAA QIF Test"), timeoutMillis = 10000)

            // The import button ("Import N Transfers") becomes enabled — preview succeeded, i.e. no
            // "Column not found" error. "Transfers" is unique to the button (the title is not).
            waitUntilAtLeastOneExists(hasText("Transfers", substring = true) and isEnabled(), timeoutMillis = 15000)
            onNodeWithText("Transfers", substring = true).performClick()

            // Dialog closes and the file is imported, so it can now be re-imported.
            waitUntilDoesNotExist(hasText("Apply Import Strategy"), timeoutMillis = 20000)
            waitForIdle()
            waitUntilAtLeastOneExists(hasText("Re-import") and isEnabled(), timeoutMillis = 15000)
        }
    }

    @Test
    fun fileWithAllDuplicateTransactions_stillShowsAsImported() {
        runMoneyManagerComposeUiTest {
            testDbLocation = createTestDatabaseLocation()
            val databaseManager = createTestDatabaseManager()

            runBlocking {
                val db = databaseManager.openDatabase(testDbLocation!!)
                val component = openDatabaseComponent(db)
                component.installBuiltInCsvStrategies()
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
                component.csvImportStrategyRepository.createStrategy(qifStrategy(source.id, target.id, usdId), Source.Manual)
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
            onNodeWithText("CSV", useUnmergedTree = true).performClick()

            // Apply to the first file (creates the transfers), then to the second (all duplicates).
            for (file in listOf("first.qif", "second.qif")) {
                // The list composes only the cards that fit (a phone's screen holds one), so scroll to the
                // row; after navigating back from the previous file the list re-renders, so wait for it first.
                waitUntilAtLeastOneExists(hasText(".qif", substring = true), timeoutMillis = 15000)
                onNode(hasScrollToIndexAction()).performScrollToNode(hasText(file))
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
            // The list composes only the cards that fit, and the two files can land in either order.
            waitUntilAtLeastOneExists(hasText(".qif", substring = true), timeoutMillis = 15000)
            onNode(hasScrollToIndexAction()).performScrollToNode(hasText("second.qif"))
            waitUntilExactlyOneExists(hasText("second.qif"), timeoutMillis = 15000)
            onNodeWithText("Unimported (0)").assertExists()
        }
    }

    @Test
    fun importAll_appliesStrategyToEveryUnimportedFileAtOnce() {
        runMoneyManagerComposeUiTest {
            testDbLocation = createTestDatabaseLocation()
            val databaseManager = createTestDatabaseManager()

            runBlocking {
                val db = databaseManager.openDatabase(testDbLocation!!)
                val component = openDatabaseComponent(db)
                component.installBuiltInCsvStrategies()
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
                component.csvImportStrategyRepository.createStrategy(qifStrategy(source.id, target.id, usdId), Source.Manual)
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
            onNodeWithText("CSV", useUnmergedTree = true).performClick()

            // The Unimported tab shows a single "Import all (2)" action.
            waitUntilExactlyOneExists(hasText("Import all (2)"), timeoutMillis = 15000)
            onNodeWithText("Import all (2)").performClick()

            // The strategy names its own source account, so nothing needs picking; confirm.
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
            // The list composes only the cards that fit, and the two files can land in either order.
            waitUntilAtLeastOneExists(hasText(".qif", substring = true), timeoutMillis = 15000)
            onNode(hasScrollToIndexAction()).performScrollToNode(hasText("second.qif"))
            waitUntilExactlyOneExists(hasText("second.qif"), timeoutMillis = 15000)
            onNodeWithText("Unimported (0)").assertExists()

            // Re-import both through the bulk dialog; nothing changed, so it re-runs them in place.
            onNodeWithText("Re-import all (2)").performClick()
            waitUntilExactlyOneExists(hasText("Re-import all imported files"), timeoutMillis = 15000)
            waitUntilAtLeastOneExists(hasText("Re-import 2 files") and isEnabled(), timeoutMillis = 15000)
            onNodeWithText("Re-import 2 files").performClick()
            waitUntilAtLeastOneExists(hasText("Re-imported 2 files", substring = true), timeoutMillis = 20000)
            onNodeWithText("Done").performClick()
            waitUntilDoesNotExist(hasText("Re-import all imported files"), timeoutMillis = 15000)
            waitForIdle()
        }
    }

    @Test
    fun unimport_oneFileThenTheRest_movesThemToIgnored() {
        runMoneyManagerComposeUiTest {
            testDbLocation = createTestDatabaseLocation()
            val databaseManager = createTestDatabaseManager()

            runBlocking {
                val db = databaseManager.openDatabase(testDbLocation!!)
                val component = openDatabaseComponent(db)
                val usdId = component.currencyRepository.upsertCurrencyByCode("USD", "US Dollar")
                component.settingsRepository.setDefaultCurrencyId(usdId)
                val source =
                    component.accountRepository.createAccount(
                        Account(id = AccountId(0), name = "Cash Account", openingDate = Instant.fromEpochMilliseconds(1700000000000L)),
                    )
                val target =
                    component.accountRepository.createAccount(
                        Account(id = AccountId(0), name = "Expenses", openingDate = Instant.fromEpochMilliseconds(1700000000000L)),
                    )
                component.csvImportStrategyRepository.createStrategy(qifStrategy(source, target, usdId), Source.Manual)
                // Identical transactions: whichever file imports second matches the first file's transfers.
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
            onNodeWithText("CSV", useUnmergedTree = true).performClick()
            waitUntilExactlyOneExists(hasText("Import all (2)"), timeoutMillis = 15000)
            onNodeWithText("Import all (2)").performClick()
            waitUntilAtLeastOneExists(hasText("Import 2 files") and isEnabled(), timeoutMillis = 15000)
            onNodeWithText("Import 2 files").performClick()
            waitUntilAtLeastOneExists(hasText("Imported 2 files", substring = true), timeoutMillis = 20000)
            onNodeWithText("Done").performClick()
            waitUntilExactlyOneExists(hasText("Imported (2)"), timeoutMillis = 15000)
            onNodeWithText("Imported (2)").performClick()
            waitForIdle()

            // One file: the preview says what it deletes, and the file leaves the Imported tab.
            waitUntilAtLeastOneExists(hasText("Unimport") and isEnabled(), timeoutMillis = 15000)
            onAllNodesWithText("Unimport").onFirst().performClick()
            waitUntilAtLeastOneExists(hasText("This deletes the", substring = true), timeoutMillis = 15000)
            waitUntilAtLeastOneExists(dialogButton("Unimport"), timeoutMillis = 15000)
            onAllNodes(dialogButton("Unimport")).onFirst().performClick()
            waitUntilAtLeastOneExists(hasText("Unimported ", substring = true), timeoutMillis = 20000)
            waitUntilExactlyOneExists(hasText("Imported (1)"), timeoutMillis = 15000)

            // The rest, through the bulk dialog.
            onNodeWithText("Unimport all (1)").performClick()
            waitUntilExactlyOneExists(hasText("Unimport 1 file(s)?"), timeoutMillis = 15000)
            waitUntilAtLeastOneExists(dialogButton("Unimport all"), timeoutMillis = 15000)
            onAllNodes(dialogButton("Unimport all")).onFirst().performClick()
            waitUntilDoesNotExist(hasText("Unimport 1 file(s)?"), timeoutMillis = 20000)
            waitForIdle()
            waitUntilExactlyOneExists(hasText("Imported (0)"), timeoutMillis = 15000)
            waitUntilExactlyOneExists(hasText("Ignored (2)"), timeoutMillis = 15000)
        }
    }

    private fun dialogButton(label: String): SemanticsMatcher = hasText(label) and isEnabled() and hasAnyAncestor(isDialog())
}

/** DatabaseManager wrapper that points the app at the pre-populated test database. */
private class QifTestDatabaseManager(
    databaseManager: DatabaseManager,
    private val testLocation: DbLocation,
) : DatabaseManager by databaseManager {
    override fun getDefaultLocation(): DbLocation = testLocation
}
