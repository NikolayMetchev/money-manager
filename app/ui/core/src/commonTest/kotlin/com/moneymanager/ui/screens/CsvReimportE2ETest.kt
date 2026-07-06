@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.ui.screens

import com.moneymanager.csvimporter.CsvTransferMapper
import com.moneymanager.csvimporter.ReimportSkipReason
import com.moneymanager.csvimporter.executeCsvReimport
import com.moneymanager.csvimporter.planCsvReimport
import com.moneymanager.csvimporter.runCsvImport
import com.moneymanager.database.port.DbMaintenance
import com.moneymanager.di.database.DatabaseComponent
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csv.ImportStatus
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
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportProgress
import com.moneymanager.importengineapi.createAccountMapping
import com.moneymanager.importer.ImportEngineImpl
import com.moneymanager.test.database.createAccount
import com.moneymanager.test.database.createTestDatabaseLocation
import com.moneymanager.test.database.createTestDatabaseManager
import com.moneymanager.test.database.deleteTestDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * End-to-end test of CSV re-import against a real database: a CSV import creates a duplicate payee
 * account; the user then adds a global account mapping pointing its value at a pre-existing account;
 * re-importing merges the duplicate into the true account (reversibly), moves its transactions, and
 * does not re-create transfers. A duplicate with transactions to the target is skipped, not merged.
 */
class CsvReimportE2ETest {
    private var testDbLocation: DbLocation? = null

    @AfterTest
    fun cleanup() {
        testDbLocation?.let { deleteTestDatabase(it) }
    }

    private class Fixture(
        val dc: DatabaseComponent,
        val importEngine: ImportEngine,
        val sourceAccountId: AccountId,
        val trueAccountId: AccountId,
        val csvImport: CsvImport,
        val strategy: CsvImportStrategy,
    )

    /**
     * Imports a two-row CSV whose payee "Amazon.com" auto-creates a duplicate account, while the
     * true account "Amazon" already exists.
     */
    private suspend fun setUpImportedCsv(): Fixture {
        testDbLocation = createTestDatabaseLocation()
        val databaseManager = createTestDatabaseManager()
        val db = databaseManager.openDatabase(testDbLocation!!)
        val dc = DatabaseComponent.create(db)
        val importEngine = createImportEngine(dc)

        val sourceAccountId =
            dc.accountRepository.createAccount(
                Account(id = AccountId(0), name = "Card", openingDate = Clock.System.now()),
            )
        val trueAccountId =
            dc.accountRepository.createAccount(
                Account(id = AccountId(0), name = "Amazon", openingDate = Clock.System.now()),
            )

        val headers = listOf("Date", "Description", "Amount", "Payee", "Local Amount")
        val csvImportId =
            dc.csvImportRepository.createImport(
                fileName = "reimport_test.csv",
                headers = headers,
                rows =
                    listOf(
                        listOf("15/12/2024", "Order 1", "-50.00", DUPLICATE_NAME, "-40.00"),
                        listOf("16/12/2024", "Order 2", "-25.00", DUPLICATE_NAME, "-20.00"),
                    ),
                fileChecksum = "reimport_test_checksum",
                fileLastModified = Instant.fromEpochMilliseconds(1_700_000_000_000L),
            )
        val csvImport = dc.csvImportRepository.getImport(csvImportId).first() ?: error("import not found")
        val rows = dc.csvImportRepository.getImportRows(csvImportId, limit = 1000, offset = 0)

        val currencies = dc.currencyRepository.getAllCurrencies().first()
        val gbp = currencies.first { it.code == "GBP" }
        val strategy = createStrategy(sourceAccountId, gbp.id)

        val mapper =
            CsvTransferMapper(
                strategy = strategy,
                columns = csvImport.columns,
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
        runCsvImport(
            csvImport = csvImport,
            rows = rows,
            columns = csvImport.columns,
            strategy = strategy,
            basePrep = mapper.prepareImport(rows),
            selectedExistingAccounts = emptyMap(),
            selectedNewAccountNames = emptyMap(),
            selectedSourceAccountId = sourceAccountId,
            currencies = currencies,
            accountMappingRepository = dc.accountMappingRepository,
            accountRepository = dc.accountRepository,
            maintenance = DbMaintenance(dc.maintenanceService),
            importEngine = importEngine,
        )

        return Fixture(dc, importEngine, sourceAccountId, trueAccountId, csvImport, strategy)
    }

    private suspend fun Fixture.duplicateAccountId(): AccountId? =
        dc.accountRepository
            .getAllAccounts()
            .first()
            .firstOrNull { it.name == DUPLICATE_NAME }
            ?.id

    @Test
    fun `re-import merges duplicate into mapped account reversibly and deletes it`() =
        runBlocking {
            val fixture = setUpImportedCsv()
            val dc = fixture.dc
            val duplicateId = fixture.duplicateAccountId() ?: error("duplicate account not created")
            assertEquals(2L, dc.accountRepository.countTransfersByAccount(duplicateId))
            assertEquals(0L, dc.accountRepository.countTransfersByAccount(fixture.trueAccountId))

            // The user notices the duplicate and adds a global mapping to the true account.
            fixture.importEngine.createAccountMapping(
                valuePattern = Regex("^Amazon\\.com$", RegexOption.IGNORE_CASE),
                accountId = fixture.trueAccountId,
            )

            val currencies = dc.currencyRepository.getAllCurrencies().first()
            val plan =
                planCsvReimport(
                    csvImport = fixture.csvImport,
                    strategy = fixture.strategy,
                    sourceAccountOverride = fixture.sourceAccountId,
                    currencies = currencies,
                    accountMappingRepository = dc.accountMappingRepository,
                    accountRepository = dc.accountRepository,
                    csvImportRepository = dc.csvImportRepository,
                    transactionRepository = dc.transactionRepository,
                    relationshipRepository = dc.transferRelationshipRepository,
                    transferSourceRepository = dc.transferSourceRepository,
                )
            assertEquals(1, plan.merges.size)
            assertEquals(duplicateId, plan.merges.single().duplicateId)
            assertEquals(fixture.trueAccountId, plan.merges.single().targetId)
            assertEquals(2L, plan.merges.single().transferCount)
            assertTrue(plan.skipped.isEmpty())

            val result =
                executeCsvReimport(
                    plan = plan,
                    csvImport = fixture.csvImport,
                    strategy = fixture.strategy,
                    sourceAccountOverride = fixture.sourceAccountId,
                    currencies = currencies,
                    accountMappingRepository = dc.accountMappingRepository,
                    accountRepository = dc.accountRepository,
                    csvImportRepository = dc.csvImportRepository,
                    maintenance = DbMaintenance(dc.maintenanceService),
                    importEngine = fixture.importEngine,
                )

            assertEquals(1, result.mergedAccounts.size)
            assertTrue(result.skipped.isEmpty())
            // The merge itself removed the duplicate; nothing was separately empty.
            assertTrue(result.deletedEmptyAccounts.isEmpty())
            // All rows were already imported, so the re-run had nothing to do (no double import).
            assertNull(result.importResult)

            // Transfers moved to the true account and the duplicate is gone.
            assertEquals(2L, dc.accountRepository.countTransfersByAccount(fixture.trueAccountId))
            assertNull(fixture.duplicateAccountId())

            // The merge is recorded and reversible.
            val reversibleMerges = dc.accountRepository.getReversibleMerges().first()
            assertEquals(1, reversibleMerges.size)
        }

    @Test
    fun `duplicate with transactions to the target account is skipped not merged`() =
        runBlocking {
            val fixture = setUpImportedCsv()
            val dc = fixture.dc
            val duplicateId = fixture.duplicateAccountId() ?: error("duplicate account not created")

            // A transfer BETWEEN the duplicate and the true account makes a merge unrepresentable
            // (it would become a self-transfer), so re-import must skip it.
            val currencies = dc.currencyRepository.getAllCurrencies().first()
            val gbp = currencies.first { it.code == "GBP" }
            dc.transactionRepository.createTransfers(
                transfers =
                    listOf(
                        Transfer(
                            id = TransferId(0),
                            timestamp = Clock.System.now(),
                            description = "Between accounts",
                            sourceAccountId = duplicateId,
                            targetAccountId = fixture.trueAccountId,
                            amount = Money(1000, gbp),
                        ),
                    ),
                sources = listOf(Source.Manual),
            )

            fixture.importEngine.createAccountMapping(
                valuePattern = Regex("^Amazon\\.com$", RegexOption.IGNORE_CASE),
                accountId = fixture.trueAccountId,
            )

            val plan =
                planCsvReimport(
                    csvImport = fixture.csvImport,
                    strategy = fixture.strategy,
                    sourceAccountOverride = fixture.sourceAccountId,
                    currencies = currencies,
                    accountMappingRepository = dc.accountMappingRepository,
                    accountRepository = dc.accountRepository,
                    csvImportRepository = dc.csvImportRepository,
                    transactionRepository = dc.transactionRepository,
                    relationshipRepository = dc.transferRelationshipRepository,
                    transferSourceRepository = dc.transferSourceRepository,
                )
            assertTrue(plan.merges.isEmpty())
            assertEquals(1, plan.skipped.size)
            assertEquals(ReimportSkipReason.TRANSFERS_BETWEEN, plan.skipped.single().reason)

            val result =
                executeCsvReimport(
                    plan = plan,
                    csvImport = fixture.csvImport,
                    strategy = fixture.strategy,
                    sourceAccountOverride = fixture.sourceAccountId,
                    currencies = currencies,
                    accountMappingRepository = dc.accountMappingRepository,
                    accountRepository = dc.accountRepository,
                    csvImportRepository = dc.csvImportRepository,
                    maintenance = DbMaintenance(dc.maintenanceService),
                    importEngine = fixture.importEngine,
                )

            assertTrue(result.mergedAccounts.isEmpty())
            assertEquals(1, result.skipped.size)
            // The duplicate keeps its transactions and must not be deleted.
            assertEquals(duplicateId, fixture.duplicateAccountId())
            assertEquals(3L, dc.accountRepository.countTransfersByAccount(duplicateId))
        }

    @Test
    fun `re-import updates transfers in place when amount and currency mappings changed`() =
        runBlocking {
            val fixture = setUpImportedCsv()
            val dc = fixture.dc

            val rowsBefore = dc.csvImportRepository.getImportRows(fixture.csvImport.id, limit = 1000, offset = 0)
            val transferIdsBefore = rowsBefore.mapNotNull { it.transferId }
            assertEquals(2, transferIdsBefore.size)

            // The user edits the strategy to read the amount from a different column in a different currency.
            val currencies = dc.currencyRepository.getAllCurrencies().first()
            val usd = currencies.first { it.code == "USD" }
            val changedStrategy =
                fixture.strategy.copy(
                    fieldMappings =
                        fixture.strategy.fieldMappings +
                            mapOf(
                                TransferField.AMOUNT to
                                    AmountParsingMapping(
                                        id = FieldMappingId(Uuid.random()),
                                        fieldType = TransferField.AMOUNT,
                                        mode = AmountMode.SINGLE_COLUMN,
                                        amountColumnName = "Local Amount",
                                    ),
                                TransferField.CURRENCY to
                                    HardCodedCurrencyMapping(
                                        id = FieldMappingId(Uuid.random()),
                                        fieldType = TransferField.CURRENCY,
                                        currencyId = usd.id,
                                    ),
                            ),
                )

            val planProgress = mutableListOf<ImportProgress>()
            val plan =
                planCsvReimport(
                    csvImport = fixture.csvImport,
                    strategy = changedStrategy,
                    sourceAccountOverride = fixture.sourceAccountId,
                    currencies = currencies,
                    accountMappingRepository = dc.accountMappingRepository,
                    accountRepository = dc.accountRepository,
                    csvImportRepository = dc.csvImportRepository,
                    transactionRepository = dc.transactionRepository,
                    relationshipRepository = dc.transferRelationshipRepository,
                    transferSourceRepository = dc.transferSourceRepository,
                    onProgress = { planProgress += it },
                )
            assertTrue(plan.merges.isEmpty())
            assertTrue(plan.skipped.isEmpty())
            assertEquals(2, plan.valueUpdates.size)
            assertTrue(plan.valueUpdates.all { it.changes.isNotEmpty() })
            // The plan reports its phases, ending the value-change scan at 2 of 2.
            assertTrue(planProgress.any { it.detail == "Analyzing rows (pass 1 of 2)" })
            val valueScan = planProgress.filter { it.detail == "Checking rows for value changes" }
            assertEquals(2, valueScan.last().processed)
            assertEquals(2, valueScan.last().total)

            val executeProgress = mutableListOf<ImportProgress>()
            val result =
                executeCsvReimport(
                    plan = plan,
                    csvImport = fixture.csvImport,
                    strategy = changedStrategy,
                    sourceAccountOverride = fixture.sourceAccountId,
                    currencies = currencies,
                    accountMappingRepository = dc.accountMappingRepository,
                    accountRepository = dc.accountRepository,
                    csvImportRepository = dc.csvImportRepository,
                    maintenance = DbMaintenance(dc.maintenanceService),
                    importEngine = fixture.importEngine,
                    onProgress = { executeProgress += it },
                    // Force one chunk per update so per-chunk progress is exercised with 2 rows.
                    valueUpdateChunkSize = 1,
                )
            assertEquals(2, result.updatedRows.size)
            assertTrue(result.skipped.isEmpty())
            // Chunked updates report 0 → 1 → 2 of 2, and the run ends with the view refresh phase.
            val updating = executeProgress.filter { it.detail == "Updating transactions" }
            assertEquals(listOf(0, 1, 2), updating.mapNotNull { it.processed })
            assertTrue(updating.all { it.total == 2 })
            assertEquals("Refreshing views", executeProgress.last().detail)
            // All rows were already imported, so nothing was re-imported (no duplicates created).
            assertNull(result.importResult)

            // The same transfers now carry the new column's values in the new currency.
            val rowsAfter = dc.csvImportRepository.getImportRows(fixture.csvImport.id, limit = 1000, offset = 0)
            assertEquals(transferIdsBefore, rowsAfter.mapNotNull { it.transferId })
            assertTrue(rowsAfter.all { it.importStatus == ImportStatus.UPDATED })
            val amountsById =
                transferIdsBefore.associateWith { id ->
                    dc.transactionRepository
                        .getTransactionById(id.id)
                        .first()
                        ?.amount
                }
            assertEquals(
                setOf(Money(4000, usd), Money(2000, usd)),
                amountsById.values.filterNotNull().toSet(),
            )

            // Re-planning right after finds nothing left to update.
            val secondPlan =
                planCsvReimport(
                    csvImport = fixture.csvImport,
                    strategy = changedStrategy,
                    sourceAccountOverride = fixture.sourceAccountId,
                    currencies = currencies,
                    accountMappingRepository = dc.accountMappingRepository,
                    accountRepository = dc.accountRepository,
                    csvImportRepository = dc.csvImportRepository,
                    transactionRepository = dc.transactionRepository,
                    relationshipRepository = dc.transferRelationshipRepository,
                    transferSourceRepository = dc.transferSourceRepository,
                )
            assertTrue(secondPlan.valueUpdates.isEmpty())
        }

    private fun createImportEngine(dc: DatabaseComponent): ImportEngine =
        ImportEngineImpl(
            transactionRepository = dc.transactionRepository,
            accountRepository = dc.accountRepository,
            accountAttributeRepository = dc.accountAttributeRepository,
            personRepository = dc.personRepository,
            personAttributeRepository = dc.personAttributeRepository,
            ownershipRepository = dc.personAccountOwnershipRepository,
            categoryRepository = dc.categoryRepository,
            currencyRepository = dc.currencyRepository,
            attributeTypeRepository = dc.attributeTypeRepository,
            relationshipTypeRepository = dc.relationshipTypeRepository,
            csvImportStrategyRepository = dc.csvImportStrategyRepository,
            apiImportStrategyRepository = dc.apiImportStrategyRepository,
            accountMappingRepository = dc.accountMappingRepository,
            csvImportRepository = dc.csvImportRepository,
            qifImportRepository = dc.qifImportRepository,
            apiSessionRepository = dc.apiSessionRepository,
            settingsRepository = dc.settingsRepository,
            importDirectoryRepository = dc.importDirectoryRepository,
            passThroughAccountRepository = dc.passThroughAccountRepository,
        )

    private fun createStrategy(
        sourceAccountId: AccountId,
        currencyId: CurrencyId,
    ): CsvImportStrategy {
        val now = Clock.System.now()
        return CsvImportStrategy(
            id = CsvImportStrategyId(Uuid.random()),
            name = "Reimport Test Strategy",
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
        const val DUPLICATE_NAME = "Amazon.com"
    }
}
