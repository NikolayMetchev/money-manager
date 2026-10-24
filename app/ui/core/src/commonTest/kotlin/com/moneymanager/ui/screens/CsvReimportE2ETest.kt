package com.moneymanager.ui.screens

import com.moneymanager.csvimporter.CsvTransferMapper
import com.moneymanager.csvimporter.runCsvImport
import com.moneymanager.database.di.DatabaseComponent
import com.moneymanager.database.di.openDatabaseComponent
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.csvstrategy.AccountFromRulesMapping
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedCurrencyMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.rules.AccountNaming
import com.moneymanager.domain.model.rules.AccountSide
import com.moneymanager.domain.model.rules.StrategyRule
import com.moneymanager.domain.model.rules.ValueExpr
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.SourceRerunResult
import com.moneymanager.importengineapi.createAccountMapping
import com.moneymanager.importer.ImportEngineImpl
import com.moneymanager.qifimporter.StagedFile
import com.moneymanager.qifimporter.StagedRunResult
import com.moneymanager.regex.compileRegex
import com.moneymanager.test.database.createAccount
import com.moneymanager.test.database.createTestDatabaseLocation
import com.moneymanager.test.database.createTestDatabaseManager
import com.moneymanager.test.database.deleteTestDatabase
import com.moneymanager.test.database.reimports
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
 * re-importing deletes what the file created and imports it again, so its rows land on the true
 * account and the emptied duplicate is deleted. A duplicate that still holds other transactions stays.
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
        val dc = openDatabaseComponent(db)
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
            maintenance = dc.maintenanceService,
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

    private suspend fun Fixture.reimport(strategy: CsvImportStrategy = this.strategy): SourceRerunResult<StagedRunResult?> {
        val reimports = dc.reimports()
        val current = dc.csvImportRepository.getImport(csvImport.id).first()!!
        val plan = reimports.plan(StagedFile.Csv(current)).copy(strategy = strategy)
        return reimports.reimport(plan, accountId = sourceAccountId, currencyId = null)
    }

    @Test
    fun `re-import moves the rows onto the mapped account and deletes the emptied duplicate`() =
        runBlocking {
            val fixture = setUpImportedCsv()
            val dc = fixture.dc
            val duplicateId = fixture.duplicateAccountId() ?: error("duplicate account not created")
            assertEquals(2L, dc.accountRepository.countTransfersByAccount(duplicateId))
            assertEquals(0L, dc.accountRepository.countTransfersByAccount(fixture.trueAccountId))

            // The user notices the duplicate and adds a global mapping to the true account.
            fixture.importEngine.createAccountMapping(
                valuePattern = compileRegex("^Amazon\\.com$", RegexOption.IGNORE_CASE),
                accountId = fixture.trueAccountId,
            )

            val result = fixture.reimport()

            assertEquals(2, result.deletedTransfers)
            assertEquals(2, result.rerun?.successCount)
            assertEquals(listOf(DUPLICATE_NAME), result.deletedEmptyAccounts)
            assertEquals(2L, dc.accountRepository.countTransfersByAccount(fixture.trueAccountId))
            assertNull(fixture.duplicateAccountId())
        }

    @Test
    fun `a duplicate still holding other transactions is kept`() =
        runBlocking {
            val fixture = setUpImportedCsv()
            val dc = fixture.dc
            val duplicateId = fixture.duplicateAccountId() ?: error("duplicate account not created")

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
                valuePattern = compileRegex("^Amazon\\.com$", RegexOption.IGNORE_CASE),
                accountId = fixture.trueAccountId,
            )

            val result = fixture.reimport()

            assertTrue(result.deletedEmptyAccounts.isEmpty())
            // The imported rows moved to the true account; the hand-made transfer keeps the duplicate.
            assertEquals(duplicateId, fixture.duplicateAccountId())
            assertEquals(1L, dc.accountRepository.countTransfersByAccount(duplicateId))
            assertEquals(3L, dc.accountRepository.countTransfersByAccount(fixture.trueAccountId))
        }

    @Test
    fun `re-import re-creates transfers with the changed amount and currency mappings`() =
        runBlocking {
            val fixture = setUpImportedCsv()
            val dc = fixture.dc

            val rowsBefore = dc.csvImportRepository.getImportRows(fixture.csvImport.id, limit = 1000, offset = 0)
            assertEquals(2, rowsBefore.mapNotNull { it.transferId }.size)

            // The user edits the strategy to read the amount from a different column in a different currency.
            val currencies = dc.currencyRepository.getAllCurrencies().first()
            val usd = currencies.first { it.code == "USD" }
            val changedStrategy =
                fixture.strategy.copy(
                    config =
                        fixture.strategy.config.copy(
                            fieldMappings =
                                fixture.strategy.config.fieldMappings +
                                    mapOf(
                                        TransferField.AMOUNT to
                                            AmountParsingMapping(
                                                fieldType = TransferField.AMOUNT,
                                                mode = AmountMode.SINGLE_COLUMN,
                                                amountColumnName = "Local Amount",
                                            ),
                                        TransferField.CURRENCY to
                                            HardCodedCurrencyMapping(
                                                fieldType = TransferField.CURRENCY,
                                                currencyId = usd.id,
                                            ),
                                    ),
                        ),
                )

            val result = fixture.reimport(changedStrategy)
            assertEquals(2, result.deletedTransfers)
            assertEquals(2, result.rerun?.successCount)

            val rowsAfter = dc.csvImportRepository.getImportRows(fixture.csvImport.id, limit = 1000, offset = 0)
            assertTrue(rowsAfter.all { it.importStatus == ImportStatus.IMPORTED })
            val amounts =
                rowsAfter.mapNotNull { row ->
                    row.transferId?.let {
                        dc.transactionRepository
                            .getTransactionById(it.id)
                            .first()
                            ?.amount
                    }
                }
            assertEquals(
                setOf(Money.fromDisplayValue("40.00", usd), Money.fromDisplayValue("20.00", usd)),
                amounts.toSet(),
            )

            // Re-importing again with the same strategy re-creates the same values.
            assertEquals(2, fixture.reimport(changedStrategy).rerun?.successCount)
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
            cryptoRepository = dc.cryptoRepository,
            tradeRepository = dc.tradeRepository,
            exchangeOrderRepository = dc.exchangeOrderRepository,
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
            config =
                CsvStrategyConfig(
                    rules = listOf(StrategyRule.accounts(AccountSide.TARGET, listOf(AccountNaming(value = ValueExpr(listOf("Payee")))))),
                    identificationColumns = setOf("Date", "Description", "Amount"),
                    fieldMappings =
                        mapOf(
                            TransferField.SOURCE_ACCOUNT to
                                HardCodedAccountMapping(
                                    fieldType = TransferField.SOURCE_ACCOUNT,
                                    accountId = sourceAccountId,
                                ),
                            TransferField.TARGET_ACCOUNT to
                                AccountFromRulesMapping(TransferField.TARGET_ACCOUNT),
                            TransferField.TIMESTAMP to
                                DateTimeParsingMapping(
                                    fieldType = TransferField.TIMESTAMP,
                                    dateColumnName = "Date",
                                    dateFormat = "dd/MM/yyyy",
                                ),
                            TransferField.DESCRIPTION to
                                DirectColumnMapping(fieldType = TransferField.DESCRIPTION, value = ValueExpr(listOf("Description"))),
                            TransferField.AMOUNT to
                                AmountParsingMapping(
                                    fieldType = TransferField.AMOUNT,
                                    mode = AmountMode.SINGLE_COLUMN,
                                    amountColumnName = "Amount",
                                ),
                            TransferField.CURRENCY to
                                HardCodedCurrencyMapping(
                                    fieldType = TransferField.CURRENCY,
                                    currencyId = currencyId,
                                ),
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
