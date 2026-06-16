@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.ui.screens
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import com.moneymanager.csvimporter.CsvTransferMapper
import com.moneymanager.csvimporter.runCsvImport
import com.moneymanager.database.DatabaseManager
import com.moneymanager.database.port.DbMaintenance
import com.moneymanager.di.database.DatabaseComponent
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvColumnId
import com.moneymanager.domain.model.csvstrategy.AccountLookupMapping
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.FieldMappingId
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedCurrencyMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.importer.ImportEngineImpl
import com.moneymanager.test.database.createAccount
import com.moneymanager.test.database.createTestDatabaseLocation
import com.moneymanager.test.database.createTestDatabaseManager
import com.moneymanager.test.database.deleteTestDatabase
import com.moneymanager.ui.test.MoneyManagerTestApp
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * End-to-end test: an account created during a CSV import shows that CSV file (and the originating
 * row) as its source in the account audit history, and the source link navigates to that row in the
 * CSV import detail.
 *
 * The import is driven through the real apply path (`runCsvImport`) before the UI launches, then the
 * UI is exercised to verify the audit trail.
 */
private class CsvAuditTestDatabaseManager(
    private val databaseManager: DatabaseManager,
    private val testLocation: DbLocation,
) : DatabaseManager by databaseManager {
    override fun getDefaultLocation(): DbLocation = testLocation
}

@OptIn(ExperimentalTestApi::class)
class CsvAccountSourceAuditE2ETest {
    private var testDbLocation: DbLocation? = null

    @AfterTest
    fun cleanup() {
        testDbLocation?.let { deleteTestDatabase(it) }
    }

    @Test
    fun `csv imported account audit shows clickable CSV source linking to its row`() =
        runMoneyManagerComposeUiTest {
            testDbLocation = createTestDatabaseLocation()
            val databaseManager = createTestDatabaseManager()

            // Pre-populate: import a CSV that creates a counterparty account, via the real apply path.
            runBlocking {
                val db = databaseManager.openDatabase(testDbLocation!!)
                val dc = DatabaseComponent.create(db)
                // A manually-created source account the imported transfer flows out of.
                val sourceAccountId =
                    dc.accountRepository.createAccount(
                        Account(id = AccountId(0), name = "Aaa Source", openingDate = Clock.System.now()),
                    )

                val headers = listOf("Date", "Description", "Amount", "Payee")
                val csvImportId =
                    dc.csvImportRepository.createImport(
                        fileName = CSV_FILE_NAME,
                        headers = headers,
                        rows = listOf(listOf("15/12/2024", "Coffee", "-50.00", COUNTERPARTY_NAME)),
                        fileChecksum = "csv_source_test_checksum",
                        fileLastModified = kotlin.time.Instant.fromEpochMilliseconds(1_700_000_000_000L),
                    )
                val csvImport = dc.csvImportRepository.getImport(csvImportId).first() ?: error("import not found")
                val rows = dc.csvImportRepository.getImportRows(csvImportId, limit = 1000, offset = 0)
                val columns =
                    headers.mapIndexed { index, name -> CsvColumn(CsvColumnId(Uuid.random()), index, name) }

                val currencies = dc.currencyRepository.getAllCurrencies().first()
                val gbp = currencies.first { it.code == "GBP" }
                val strategy = createStrategy(sourceAccountId, gbp.id)

                val mapper =
                    CsvTransferMapper(
                        strategy = strategy,
                        columns = columns,
                        existingAccounts =
                            dc.accountRepository
                                .getAllAccounts()
                                .first()
                                .associateBy { it.name },
                        existingCurrencies = currencies.associateBy { it.id },
                        existingCurrenciesByCode = currencies.associateBy { it.code.uppercase() },
                        accountMappings = emptyList(),
                        sourceAccountOverride = sourceAccountId,
                    )
                val basePrep = mapper.prepareImport(rows)

                runCsvImport(
                    csvImport = csvImport,
                    rows = rows,
                    columns = columns,
                    strategy = strategy,
                    basePrep = basePrep,
                    selectedExistingAccounts = emptyMap(),
                    selectedNewAccountNames = emptyMap(),
                    selectedSourceAccountId = sourceAccountId,
                    currencies = currencies,
                    csvAccountMappingRepository = dc.csvAccountMappingRepository,
                    accountRepository = dc.accountRepository,
                    csvImportRepository = dc.csvImportRepository,
                    attributeTypeRepository = dc.attributeTypeRepository,
                    maintenance = DbMaintenance(dc.maintenanceService),
                    importEngine =
                        ImportEngineImpl(
                            transactionRepository = dc.transactionRepository,
                            accountRepository = dc.accountRepository,
                            accountAttributeRepository = dc.accountAttributeRepository,
                            personRepository = dc.personRepository,
                            personAttributeRepository = dc.personAttributeRepository,
                            ownershipRepository = dc.personAccountOwnershipRepository,
                        ),
                )
            }

            setContent {
                MoneyManagerTestApp(
                    databaseManager = CsvAuditTestDatabaseManager(databaseManager, testDbLocation!!),
                    appVersion = AppVersion("1.0.0-test"),
                )
            }

            waitForIdle()
            // The counterparty account was created by the CSV import.
            waitUntilAtLeastOneExists(hasText(COUNTERPARTY_NAME), timeoutMillis = 20000)

            // Open the counterparty account's audit history. Accounts are ordered by name, so
            // "Zzz ..." sorts after "Aaa Source" — its audit (📋) button is the last one.
            onAllNodesWithText("📋").onLast().performClick()
            waitForIdle()

            // The audit shows the CSV import as the source (no longer "Source data missing"),
            // with the file name and originating row rendered as clickable links.
            waitUntilAtLeastOneExists(hasText("CSV Import"), timeoutMillis = 10000)
            waitUntilDoesNotExist(hasText("Source data missing"), timeoutMillis = 3000)
            waitForIdle()
            waitUntilAtLeastOneExists(hasText(CSV_FILE_NAME), timeoutMillis = 5000)
            waitUntilAtLeastOneExists(hasText("Row:"), timeoutMillis = 5000)

            // Clicking the CSV file link navigates to that row in the CSV import detail screen.
            onNodeWithText(CSV_FILE_NAME).performClick()
            waitForIdle()
            waitUntilAtLeastOneExists(hasText("1 rows", substring = true), timeoutMillis = 10000)
        }

    private fun createStrategy(
        sourceAccountId: AccountId,
        currencyId: CurrencyId,
    ): CsvImportStrategy {
        val now = Clock.System.now()
        return CsvImportStrategy(
            id = CsvImportStrategyId(Uuid.random()),
            name = "Test CSV Source Strategy",
            identificationColumns = setOf("Date", "Description", "Amount"),
            fieldMappings =
                mapOf(
                    TransferField.SOURCE_ACCOUNT to
                        HardCodedAccountMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.SOURCE_ACCOUNT,
                            accountId = sourceAccountId,
                        ),
                    TransferField.TARGET_ACCOUNT to
                        AccountLookupMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.TARGET_ACCOUNT,
                            columnName = "Payee",
                        ),
                    TransferField.TIMESTAMP to
                        DateTimeParsingMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.TIMESTAMP,
                            dateColumnName = "Date",
                            dateFormat = "dd/MM/yyyy",
                        ),
                    TransferField.DESCRIPTION to
                        DirectColumnMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.DESCRIPTION,
                            columnName = "Description",
                        ),
                    TransferField.AMOUNT to
                        AmountParsingMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.AMOUNT,
                            mode = AmountMode.SINGLE_COLUMN,
                            amountColumnName = "Amount",
                        ),
                    TransferField.CURRENCY to
                        HardCodedCurrencyMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.CURRENCY,
                            currencyId = currencyId,
                        ),
                ),
            createdAt = now,
            updatedAt = now,
        )
    }

    private companion object {
        const val CSV_FILE_NAME = "csv_source_test.csv"
        const val COUNTERPARTY_NAME = "Zzz Counterparty Ltd"
    }
}
