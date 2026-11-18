package com.moneymanager.database.csv

import com.moneymanager.csvimporter.applyStagedCsv
import com.moneymanager.database.di.createImportEngine
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedTimezoneMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.rules.ValueExpr
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.EditGate
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportOperation
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.PlannedField
import com.moneymanager.importengineapi.StaleImportPlanException
import com.moneymanager.importengineapi.createAccount
import com.moneymanager.importengineapi.updateCsvStrategy
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.planFile
import com.moneymanager.test.database.reimports
import com.moneymanager.test.database.upsertCurrencyByCode
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class CsvReadOnlyPreviewTest : DbTest() {
    private val now = Instant.fromEpochMilliseconds(1_765_000_000_000)
    private val headings = listOf("Date", "Time", "Currency", "Amount", "Corrected", "Description", "Corrected description")

    @Test
    fun previewIsReadOnlyAndApplyKeepsIdsAndOverrides() =
        runDbTest {
            val fixture = stage()
            val (id, strategy) = fixture
            val original = transfer(id)
            editDescription(original, "My description")
            val before = transfer(id)
            repositories.importEngine.updateCsvStrategy(corrected(strategy))
            val staged = rows(id)
            val hashes = repositories.importRecordRepository.getCsvImportRecords(id)
            val balances = repositories.transactionRepository.getAccountBalances().first()
            val version = repositories.importEngine.writeVersion.value
            val reimports = repositories.reimports()
            val plan = assertNotNull(reimports.preview(reimports.planFile(id), null, null))

            assertEquals(version, repositories.importEngine.writeVersion.value)
            assertEquals(before, transfer(id))
            assertEquals(staged, rows(id))
            assertEquals(hashes, repositories.importRecordRepository.getCsvImportRecords(id))
            assertEquals(balances, repositories.transactionRepository.getAccountBalances().first())
            assertEquals(
                listOf(PlannedField.AMOUNT),
                plan.preview.updated
                    .single()
                    .fields,
            )
            assertEquals(
                "My description",
                plan.preview.conflicts
                    .single()
                    .overrideValue,
            )

            val result = reimports.applyPreview(plan)
            val after = transfer(id)
            assertEquals(plan.preview, result.plan)
            assertEquals(original.id, after.id)
            assertEquals("My description", after.description)
            assertEquals(Money.fromDisplayValue("12.00", after.amount.asset), after.amount)
            assertEquals(staged, rows(id))
            assertEquals(ImportStatus.IMPORTED, rows(id).single().importStatus)
            val afterBalances =
                repositories.transactionRepository.getAccountBalances().first().associate {
                    it.accountId to
                        it.balance.amount
                }
            assertEquals(-after.amount.amount, afterBalances[fixture.bank])
            assertEquals(after.amount.amount, afterBalances[fixture.shop])
            assertFailsWith<StaleImportPlanException> { reimports.applyPreview(plan) }

            val noChange = assertNotNull(reimports.preview(reimports.planFile(id), null, null))
            assertTrue(noChange.preview.isEmpty)
            val appliedVersion = repositories.importEngine.writeVersion.value
            assertEquals(1, reimports.applyPreview(noChange).filesUnchanged)
            assertEquals(appliedVersion, repositories.importEngine.writeVersion.value)
        }

    @Test
    fun stalePreviewCannotOverwriteANewerEdit() =
        runDbTest {
            val (id, strategy) = stage()
            repositories.importEngine.updateCsvStrategy(corrected(strategy))
            val reimports = repositories.reimports()
            val plan = assertNotNull(reimports.preview(reimports.planFile(id), null, null))
            val original = transfer(id)
            val version = repositories.importEngine.writeVersion.value
            val otherEngine = repositories.createImportEngine(EditGate.AlwaysWritable)
            editDescription(original, "Later edit", otherEngine)
            assertEquals(version, repositories.importEngine.writeVersion.value)
            val edited = transfer(id)
            val hashes = repositories.importRecordRepository.getCsvImportRecords(id)

            assertFailsWith<StaleImportPlanException> { reimports.applyPreview(plan) }
            assertEquals(original.amount, edited.amount)
            assertEquals("Later edit", edited.description)
            assertEquals(edited, transfer(id))
            assertEquals(hashes, repositories.importRecordRepository.getCsvImportRecords(id))
        }

    private suspend fun rows(id: CsvImportId) = repositories.csvImportRepository.getImportRows(id, limit = 10, offset = 0)

    private suspend fun transfer(id: CsvImportId): Transfer =
        repositories.transactionRepository
            .getTransactionsByIds(listOf(assertNotNull(rows(id).single().transferId)))
            .values
            .single()

    private suspend fun editDescription(
        transfer: Transfer,
        description: String,
        engine: ImportEngine = repositories.importEngine,
    ) {
        engine.import(
            ImportBatch.manualEdits(
                transfers =
                    listOf(
                        ImportTransfer(
                            source = Source.Manual,
                            operation = ImportOperation.UPDATE,
                            existingId = transfer.id,
                            fromAccount = AccountRef.Existing(transfer.sourceAccountId),
                            toAccount = AccountRef.Existing(transfer.targetAccountId),
                            timestamp = transfer.timestamp,
                            description = description,
                            amount = transfer.amount,
                        ),
                    ),
            ),
        )
    }

    private fun corrected(strategy: CsvImportStrategy): CsvImportStrategy {
        val amount = AmountParsingMapping(TransferField.AMOUNT, AmountMode.SINGLE_COLUMN, amountColumnName = "Corrected")
        val description = DirectColumnMapping(TransferField.DESCRIPTION, ValueExpr(listOf("Corrected description")))
        return strategy.copy(
            config =
                strategy.config.copy(
                    fieldMappings =
                        strategy.config.fieldMappings + mapOf(TransferField.AMOUNT to amount, TransferField.DESCRIPTION to description),
                ),
        )
    }

    private suspend fun stage(): Fixture {
        val db = repositories
        val engine = db.importEngine
        val bank = engine.createAccount(Account(id = AccountId(0), name = "Bank", openingDate = now), Source.Manual)
        val shop = engine.createAccount(Account(id = AccountId(0), name = "Shop", openingDate = now), Source.Manual)
        db.currencyRepository.upsertCurrencyByCode("GBP", "Pound sterling")
        val strategy =
            CsvImportStrategy(
                id = CsvImportStrategyId(Uuid.random()),
                name = "Independent transfers",
                config =
                    CsvStrategyConfig(
                        identificationColumns = headings.toSet(),
                        fieldMappings =
                            mapOf(
                                TransferField.TIMESTAMP to
                                    DateTimeParsingMapping(TransferField.TIMESTAMP, "Date", "yyyy-MM-dd", "Time", "HH:mm:ss"),
                                TransferField.SOURCE_ACCOUNT to HardCodedAccountMapping(TransferField.SOURCE_ACCOUNT, bank),
                                TransferField.TARGET_ACCOUNT to HardCodedAccountMapping(TransferField.TARGET_ACCOUNT, shop),
                                TransferField.DESCRIPTION to
                                    DirectColumnMapping(TransferField.DESCRIPTION, ValueExpr(listOf("Description"))),
                                TransferField.AMOUNT to
                                    AmountParsingMapping(TransferField.AMOUNT, AmountMode.SINGLE_COLUMN, amountColumnName = "Amount"),
                                TransferField.CURRENCY to CurrencyLookupMapping(TransferField.CURRENCY, ValueExpr(listOf("Currency"))),
                                TransferField.TIMEZONE to HardCodedTimezoneMapping(TransferField.TIMEZONE, "UTC"),
                            ),
                    ),
                createdAt = now,
                updatedAt = now,
            )
        db.csvImportStrategyRepository.createStrategy(strategy, Source.Manual)
        val id =
            db.csvImportRepository.createImport(
                fileName = "independent.csv",
                headers = headings,
                rows = listOf(listOf("2026-01-01", "10:00:00", "GBP", "10.00", "12.00", "Old description", "New description")),
                fileChecksum = "independent",
                fileLastModified = now,
            )
        assertNotNull(
            applyStagedCsv(
                csvImport = assertNotNull(db.csvImportRepository.getImport(id).first()),
                strategy = strategy,
                sourceAccountOverride = null,
                currencies = db.currencyRepository.getAllCurrencies().first(),
                accountMappingRepository = db.accountMappingRepository,
                accountRepository = db.accountRepository,
                csvImportRepository = db.csvImportRepository,
                maintenance = db.maintenanceService,
                importEngine = engine,
                refreshViews = true,
            ),
        )
        return Fixture(id, strategy, bank, shop)
    }

    private data class Fixture(
        val id: CsvImportId,
        val strategy: CsvImportStrategy,
        val bank: AccountId,
        val shop: AccountId,
    )
}
