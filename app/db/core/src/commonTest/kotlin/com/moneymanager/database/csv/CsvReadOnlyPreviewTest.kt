package com.moneymanager.database.csv

import app.cash.sqldelight.async.coroutines.awaitAsList
import com.moneymanager.csvimporter.applyStagedCsv
import com.moneymanager.database.di.createImportEngine
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.csv.CsvRowLink
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedCurrencyMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedTimezoneMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.ConditionOp
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.StrategyRule
import com.moneymanager.domain.model.rules.ValueExpr
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.CsvImportMutation
import com.moneymanager.importengineapi.EditGate
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportOperation
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.PlannedField
import com.moneymanager.importengineapi.StaleImportPlanException
import com.moneymanager.importengineapi.applyCsvImportMutations
import com.moneymanager.importengineapi.createAccount
import com.moneymanager.importengineapi.updateCsvStrategy
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.planFile
import com.moneymanager.test.database.read.testReads
import com.moneymanager.test.database.reimportFile
import com.moneymanager.test.database.reimports
import com.moneymanager.test.database.runPlan
import com.moneymanager.test.database.upsertCurrencyByCode
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class CsvReadOnlyPreviewTest : DbTest() {
    private val now = Instant.fromEpochMilliseconds(1_765_000_000_000)
    private val headings = listOf("Date", "Time", "Currency", "Amount", "Corrected", "Description", "Corrected description")

    @Test
    fun reimportChangesTheDateAccountAndCurrencyUnderTheExistingId() =
        runDbTest {
            val (id, strategy) = stage()
            val engine = repositories.importEngine
            val original = transfer(id)
            val target = engine.createAccount(Account(id = AccountId(0), name = "New destination", openingDate = now), Source.Manual)
            repositories.currencyRepository.upsertCurrencyByCode("EUR", "Euro")
            val euro = assertNotNull(repositories.currencyRepository.getCurrencyByCode("EUR").first())
            val corrections =
                mapOf(
                    TransferField.TARGET_ACCOUNT to HardCodedAccountMapping(TransferField.TARGET_ACCOUNT, target),
                    TransferField.CURRENCY to HardCodedCurrencyMapping(TransferField.CURRENCY, euro.id),
                    TransferField.TIMEZONE to HardCodedTimezoneMapping(TransferField.TIMEZONE, "America/New_York"),
                )
            engine.updateCsvStrategy(
                strategy.copy(config = strategy.config.copy(fieldMappings = strategy.config.fieldMappings + corrections)),
            )
            val before = transferAuditCount()

            val result = repositories.reimports().reimportFile(id)

            val after = transfer(id)
            assertEquals(original.id, after.id)
            assertEquals(target, after.targetAccountId)
            assertEquals(Money.fromDisplayValue("10.00", euro), after.amount)
            assertEquals(Instant.parse("2026-01-01T15:00:00Z"), after.timestamp)
            val updated = result.plan.updated.single()
            assertEquals(setOf(PlannedField.TIME, PlannedField.ACCOUNTS, PlannedField.AMOUNT), updated.fields.toSet())
            assertEquals(before + 1, transferAuditCount())
        }

    @Test
    fun reimportOfAReplacedFileOnlyInsertsTheNewRow() =
        runDbTest {
            val (id) = stage()
            val original = rows(id).single()
            repositories.importEngine.applyCsvImportMutations(
                listOf(
                    CsvImportMutation.Repopulate(
                        id,
                        headings,
                        listOf(original.values, listOf("2026-01-02", "11:00:00", "GBP", "5.00", "5.00", "New", "New")),
                        fileChecksum = "independent-replaced",
                        fileLastModified = now,
                        carriedRows = listOf(CsvRowLink(1, ImportStatus.IMPORTED)),
                        rowIndexRemap = mapOf(original.rowIndex to 1L),
                    ),
                ),
            )
            val before = transferAuditCount()
            val reimports = repositories.reimports()

            val result = reimports.reimportFile(id)

            assertEquals(1, result.transfersCreated)
            val after = rows(id)
            assertEquals(original, after[0])
            val created = result.plan.created.single()
            assertEquals(created.id, assertNotNull(after[1].transferId))
            assertEquals(ImportStatus.IMPORTED, after[1].importStatus)
            assertEquals(2, repositories.importRecordRepository.getCsvImportRecords(id).size)
            assertEquals(before + 1, transferAuditCount())
            assertEquals(1, reimports.reimportFile(id).filesUnchanged)
        }

    @Test
    fun standardReimportUpdatesOnlyTheChangedTransfer() =
        runDbTest {
            val (id, strategy) = stage(includeUnchangedRow = true)
            val staged = rows(id)
            val correctedId = assertNotNull(staged[0].transferId)
            val unchangedId = assertNotNull(staged[1].transferId)
            val transfers = repositories.transactionRepository
            val neighbor = transfers.getTransactionsByIds(listOf(unchangedId)).getValue(unchangedId)
            repositories.importEngine.updateCsvStrategy(corrected(strategy))
            val before = transferAuditCount()
            val reimports = repositories.reimports()

            val result = reimports.reimportFile(id)

            assertEquals(1, result.filesImported)
            assertEquals(0, result.transfersCreated)
            val update = result.plan.updated.single()
            assertEquals(correctedId, update.after.id)
            assertEquals(staged, rows(id))
            val after = transfers.getTransactionsByIds(listOf(correctedId, unchangedId))
            val corrected = after.getValue(correctedId)
            assertEquals(Money.fromDisplayValue("12.00", corrected.amount.asset), corrected.amount)
            assertEquals(neighbor, after.getValue(unchangedId))
            assertEquals(before + 1, transferAuditCount())

            val version = repositories.importEngine.writeVersion.value
            val repeated = reimports.reimportFile(id)
            assertEquals(1, repeated.filesUnchanged)
            assertEquals(version, repositories.importEngine.writeVersion.value)
            assertEquals(before + 1, transferAuditCount())
        }

    @Test
    fun bulkReimportLeavesNonMatchingTransfersOnSharedAccountsAlone() =
        runDbTest {
            val first = stage()
            val second = stage(suffix = "-shared", existingAccounts = first.bank to first.shop)
            val original = transfer(first.id)
            val neighbor = transfer(second.id)
            val engine = repositories.importEngine
            engine.updateCsvStrategy(corrected(first.strategy))
            val reimports = repositories.reimports()
            val files = listOf(first, second).map { assertNotNull(repositories.csvImportRepository.getImport(it.id).first()) }
            val before = transferAuditCount()

            val result = reimports.runPlan(reimports.planAll(files))

            assertEquals(1, result.filesImported)
            assertEquals(1, result.filesUnchanged)
            val updated = result.plan.updated.single()
            assertEquals(original.id, updated.after.id)
            assertEquals(original.id, transfer(first.id).id)
            assertEquals(neighbor, transfer(second.id))
            assertEquals(before + 1, transferAuditCount())
        }

    @Test
    fun bulkReimportCommitsOnlyTheDeltasOfIndependentCsvFiles() =
        runDbTest {
            val fixtures = listOf(stage(includeUnchangedRow = true), stage(includeUnchangedRow = true, suffix = "-second"))
            val originalRows = fixtures.map { rows(it.id) }
            val engine = repositories.importEngine
            fixtures.forEach { (_, strategy) -> engine.updateCsvStrategy(skipFirst(strategy)) }
            val reimports = repositories.reimports()
            val files = fixtures.map { assertNotNull(repositories.csvImportRepository.getImport(it.id).first()) }
            val before = transferAuditCount()

            val removed = reimports.runPlan(reimports.planAll(files))

            assertEquals(2, removed.filesImported)
            val removedIds: Set<TransactionId> = originalRows.map { assertNotNull(it.first().transferId) }.toSet()
            assertEquals(
                removedIds,
                removed.plan.removed
                    .map { it.id }
                    .toSet(),
            )
            assertEquals(before + 2, transferAuditCount())
            fixtures.forEach { (_, strategy) -> engine.updateCsvStrategy(strategy) }

            val restored = reimports.runPlan(reimports.planAll(files))

            assertEquals(2, restored.filesImported)
            assertEquals(2, restored.transfersCreated)
            val after = fixtures.map { rows(it.id) }
            val restoredIds: Set<TransactionId> = after.map { assertNotNull(it.first().transferId) }.toSet()
            assertEquals(
                restoredIds,
                restored.plan.created
                    .map { it.id }
                    .toSet(),
            )
            assertEquals(originalRows.map { it.last() }, after.map { it.last() })
            assertEquals(before + 4, transferAuditCount())
        }

    @Test
    fun standardReimportRemovesAndRestoresOnlyTheRowAffectedByASkipRule() =
        runDbTest {
            val (id, strategy) = stage(includeUnchangedRow = true)
            val staged = rows(id)
            val removedId = assertNotNull(staged[0].transferId)
            val neighbor = staged[1]
            val original = repositories.transactionRepository.getTransactionsByIds(listOf(removedId)).getValue(removedId)
            editDescription(original, "My description")
            val engine = repositories.importEngine
            engine.updateCsvStrategy(skipFirst(strategy))
            val reimports = repositories.reimports()
            val before = transferAuditCount()

            val removed = reimports.reimportFile(id)

            assertEquals(
                removedId,
                removed.plan.removed
                    .single()
                    .id,
            )
            assertTrue(removedId in removed.plan.releasedOverrides)
            val afterRemoval = rows(id)
            assertEquals(ImportStatus.SKIPPED, afterRemoval[0].importStatus)
            assertNull(afterRemoval[0].transferId)
            assertEquals(neighbor, afterRemoval[1])
            assertEquals(before + 1, transferAuditCount())

            engine.updateCsvStrategy(strategy)
            val restored = reimports.reimportFile(id)

            assertEquals(1, restored.transfersCreated)
            val afterRestore = rows(id)
            assertEquals(ImportStatus.IMPORTED, afterRestore[0].importStatus)
            assertEquals(
                assertNotNull(afterRestore[0].transferId),
                restored.plan.created
                    .single()
                    .id,
            )
            assertEquals(neighbor, afterRestore[1])
            assertEquals(before + 2, transferAuditCount())
        }

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

    private suspend fun transferAuditCount(): Long =
        database.testReads.rowCountSelectQueries
            .countAuditRowsByTable()
            .awaitAsList()
            .single { it.table_name == "transfer_audit" }
            .row_count

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

    private fun skipFirst(strategy: CsvImportStrategy): CsvImportStrategy {
        val skip =
            StrategyRule(
                conditions = listOf(Condition("Description", ConditionOp.EQUALS, "Old description")),
                actions = listOf(RuleAction.Skip),
            )
        return strategy.copy(config = strategy.config.copy(rules = listOf(skip)))
    }

    private suspend fun stage(
        includeUnchangedRow: Boolean = false,
        suffix: String = "",
        existingAccounts: Pair<AccountId, AccountId>? = null,
    ): Fixture {
        val db = repositories
        val engine = db.importEngine
        val bank =
            existingAccounts?.first
                ?: engine.createAccount(Account(id = AccountId(0), name = "Bank$suffix", openingDate = now), Source.Manual)
        val shop =
            existingAccounts?.second
                ?: engine.createAccount(Account(id = AccountId(0), name = "Shop$suffix", openingDate = now), Source.Manual)
        db.currencyRepository.upsertCurrencyByCode("GBP", "Pound sterling")
        val strategy =
            CsvImportStrategy(
                id = CsvImportStrategyId(Uuid.random()),
                name = "Independent transfers$suffix",
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
                fileName = "independent$suffix.csv",
                headers = headings,
                rows =
                    buildList {
                        if (existingAccounts == null) {
                            add(listOf("2026-01-01", "10:00:00", "GBP", "10.00", "12.00", "Old description", "New description"))
                        } else {
                            add(listOf("2026-02-15", "10:00:00", "GBP", "50.00", "50.00", "Other statement", "Other statement"))
                        }
                        if (includeUnchangedRow) {
                            add(listOf("2026-01-02", "11:00:00", "GBP", "5.00", "5.00", "Unchanged", "Unchanged"))
                        }
                    },
                fileChecksum = "independent$suffix",
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
