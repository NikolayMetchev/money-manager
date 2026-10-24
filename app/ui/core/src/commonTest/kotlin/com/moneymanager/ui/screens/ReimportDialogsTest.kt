package com.moneymanager.ui.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import com.moneymanager.csvimporter.AttributeAccountMatcher
import com.moneymanager.csvimporter.bulkApplyCsv
import com.moneymanager.database.di.DatabaseComponent
import com.moneymanager.database.di.openDatabaseComponent
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.ConditionOp
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.StrategyRule
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportOperation
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.SourceRerunResult
import com.moneymanager.importengineapi.createAccount
import com.moneymanager.importengineapi.getOrCreateAttributeType
import com.moneymanager.importengineapi.updateCsvStrategy
import com.moneymanager.presenter.error.ProvideSchemaAwareScope
import com.moneymanager.qifimporter.StagedFile
import com.moneymanager.qifimporter.StagedRunResult
import com.moneymanager.test.database.createTestDatabaseLocation
import com.moneymanager.test.database.createTestDatabaseManager
import com.moneymanager.test.database.deleteTestDatabase
import com.moneymanager.test.database.installBuiltInCsvStrategies
import com.moneymanager.test.database.reimports
import com.moneymanager.test.database.upsertCurrencyByCode
import com.moneymanager.ui.components.imports.ReimportAllDialog
import com.moneymanager.ui.components.imports.ReimportDialog
import com.moneymanager.ui.components.imports.ReimportPickerRepositories
import com.moneymanager.ui.components.imports.toSummary
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Clock

/**
 * The CSV/QIF re-import dialogs over a real database: a Monzo export whose "Coffee" transaction was
 * edited by hand previews that edit, re-imports, and reports an edit it couldn't put back.
 */
@OptIn(ExperimentalTestApi::class)
class ReimportDialogsTest {
    private var testDbLocation: DbLocation? = null

    @AfterTest
    fun cleanup() {
        testDbLocation?.let { deleteTestDatabase(it) }
    }

    private class Fixture(
        val dc: DatabaseComponent,
        val csvImport: CsvImport,
    ) {
        val pickers =
            ReimportPickerRepositories(dc.accountRepository, dc.categoryRepository, dc.currencyRepository, dc.personRepository)

        suspend fun reloaded() = StagedFile.Csv(dc.csvImportRepository.getImport(csvImport.id).first()!!)
    }

    /** Imports a two-row Monzo export onto "Monzo" and edits the first row's transaction by hand. */
    private fun setUp(): Fixture =
        runBlocking {
            testDbLocation = createTestDatabaseLocation()
            val dc = openDatabaseComponent(createTestDatabaseManager().openDatabase(testDbLocation!!))
            dc.installBuiltInCsvStrategies()
            dc.currencyRepository.upsertCurrencyByCode("GBP", "British Pound")
            val now = Clock.System.now()
            val monzo = dc.importEngine.createAccount(Account(id = AccountId(0), name = "Monzo", openingDate = now), Source.Manual)
            val importId =
                dc.csvImportRepository.createImport(
                    fileName = "MonzoDataExport.csv",
                    headers = MONZO_HEADERS,
                    rows = listOf(monzoRow("tx_1", "Coffee Shop", "-3.50", "Coffee"), monzoRow("tx_2", "Book Store", "-12.00", "Books")),
                    fileChecksum = "monzo-checksum",
                    fileLastModified = now,
                )
            bulkApplyCsv(
                imports = listOf(dc.csvImportRepository.getImport(importId).first()!!),
                sourceAccountOverride = monzo,
                strategies = dc.csvImportStrategyRepository.getAllStrategies().first(),
                currencies = dc.currencyRepository.getAllCurrencies().first(),
                accountMappingRepository = dc.accountMappingRepository,
                accountRepository = dc.accountRepository,
                csvImportRepository = dc.csvImportRepository,
                maintenance = dc.maintenanceService,
                importEngine = dc.importEngine,
                onProgress = { },
                cryptoRepository = dc.cryptoRepository,
                attributeAccountMatchers = AttributeAccountMatcher.registry(dc.accountAttributeRepository.getAll().first()),
            )
            val transferId = assertNotNull(dc.csvImportRepository.getImportRows(importId, limit = 10, offset = 0)[0].transferId)
            val coffee = dc.transactionRepository.getTransactionsByIds(listOf(transferId)).getValue(transferId)
            val noteType = dc.importEngine.getOrCreateAttributeType("note")
            dc.importEngine.import(
                ImportBatch.manualEdits(
                    transfers =
                        listOf(
                            ImportTransfer(
                                source = Source.Manual,
                                operation = ImportOperation.UPDATE,
                                existingId = coffee.id,
                                fromAccount = AccountRef.Existing(coffee.sourceAccountId),
                                toAccount = AccountRef.Existing(coffee.targetAccountId),
                                timestamp = coffee.timestamp,
                                description = "Flat white",
                                amount = coffee.amount,
                                attributes = listOf(NewAttribute(noteType, "work trip")),
                            ),
                        ),
                ),
            )
            Fixture(dc, dc.csvImportRepository.getImport(importId).first()!!)
        }

    @Test
    fun reimportDialog_previewsTheHandEdit_andReappliesIt() =
        runMoneyManagerComposeUiTest {
            val fixture = setUp()
            var result: SourceRerunResult<StagedRunResult?>? = null

            setContent {
                ProvideSchemaAwareScope {
                    ReimportDialog(
                        file = StagedFile.Csv(fixture.csvImport),
                        reimports = fixture.dc.reimports(),
                        pickers = fixture.pickers,
                        onDismiss = { },
                        onComplete = { result = it },
                    )
                }
            }

            waitUntilAtLeastOneExists(hasText("Manual edits to re-apply (1):"), timeoutMillis = 15_000)
            onNodeWithText("Deletes the 2 transactions", substring = true).assertExists()
            onNodeWithText("Description → \"Flat white\"", substring = true).assertExists()
            onNodeWithText("note → work trip", substring = true).assertExists()

            onNodeWithText("Re-import").performClick()
            waitUntil(timeoutMillis = 15_000) { result != null }

            val finished = result!!
            assertEquals(1, finished.reappliedEdits.size)
            assertEquals(
                "Re-import complete: 2 transactions deleted, 2 imported, 1 manual edit re-applied",
                finished.toSummary(imported = finished.rerun?.successCount ?: 0),
            )
        }

    @Test
    fun reimportAllDialog_reportsAHandEditItCouldNotReapply() =
        runMoneyManagerComposeUiTest {
            val fixture = setUp()
            // The strategy now skips the edited row, so its transaction isn't created again.
            runBlocking {
                val strategy =
                    assertNotNull(
                        fixture.dc
                            .reimports()
                            .plan(fixture.reloaded())
                            .strategy,
                    )
                val skipCoffee =
                    StrategyRule(
                        conditions = listOf(Condition("Transaction ID", ConditionOp.EQUALS, "tx_1")),
                        actions = listOf(RuleAction.Skip),
                    )
                fixture.dc.importEngine.updateCsvStrategy(
                    strategy.copy(config = strategy.config.copy(rules = listOf(skipCoffee) + strategy.config.rules)),
                )
            }
            var completed = false

            setContent {
                ProvideSchemaAwareScope {
                    ReimportAllDialog(
                        files = listOf(StagedFile.Csv(fixture.csvImport)),
                        reimports = fixture.dc.reimports(),
                        pickers = fixture.pickers,
                        onDismiss = { },
                        onComplete = { completed = true },
                    )
                }
            }

            waitUntilAtLeastOneExists(hasText("Manual edits to re-apply (1):"), timeoutMillis = 15_000)
            onNodeWithText("Re-import 1 files").performClick()

            waitUntilAtLeastOneExists(hasText("Manual edits not re-applied (1):"), timeoutMillis = 15_000)
            onNodeWithText("Flat white", substring = true).assertExists()
            assertEquals(false, completed)
        }

    private companion object {
        val MONZO_HEADERS =
            listOf(
                "Transaction ID",
                "Date",
                "Time",
                "Type",
                "Name",
                "Emoji",
                "Category",
                "Amount",
                "Currency",
                "Local amount",
                "Local currency",
                "Notes and #tags",
                "Address",
                "Receipt",
                "Description",
                "Category split",
                "Money Out",
                "Money In",
            )

        fun monzoRow(
            transactionId: String,
            name: String,
            amount: String,
            description: String,
        ): List<String> =
            listOf(
                transactionId,
                "19/11/2023",
                "21:15:00",
                "Faster payment",
                name,
                "",
                "Transfers",
                amount,
                "GBP",
                amount,
                "GBP",
                "",
                "",
                "",
                description,
                "",
                "",
                "",
            )
    }
}
