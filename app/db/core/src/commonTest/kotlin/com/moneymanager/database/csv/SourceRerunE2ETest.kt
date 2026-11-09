package com.moneymanager.database.csv

import com.moneymanager.csvimporter.AttributeAccountMatcher
import com.moneymanager.csvimporter.BulkApplyResult
import com.moneymanager.csvimporter.bulkApplyCsv
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.ConditionOp
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.StrategyRule
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.CsvImportMutation
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportOperation
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.RerunBlockedException
import com.moneymanager.importengineapi.applyCsvImportMutations
import com.moneymanager.importengineapi.createAccount
import com.moneymanager.importengineapi.getOrCreateAttributeType
import com.moneymanager.importengineapi.updateCsvStrategy
import com.moneymanager.tasks.Progress
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.planFile
import com.moneymanager.test.database.reimportFile
import com.moneymanager.test.database.reimports
import com.moneymanager.test.database.runPlan
import com.moneymanager.test.database.upsertCurrencyByCode
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Re-import deletes what a file created and imports it again, then puts back what a person edited by
 * hand and re-runs the other files' rows that were matched against the deleted transactions.
 */
class SourceRerunE2ETest : DbTest() {
    override val installBuiltInStrategies: Boolean = true

    private val now = Clock.System.now()

    private val maintenance =
        object : Maintenance {
            override suspend fun reindex(): Duration = Duration.ZERO

            override suspend fun vacuum(): Duration = Duration.ZERO

            override suspend fun analyze(): Duration = Duration.ZERO

            override suspend fun refreshMaterializedViews(): Duration = Duration.ZERO

            override suspend fun fullRefreshMaterializedViews(): Duration = Duration.ZERO
        }

    private val headers =
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

    private fun row(
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

    private suspend fun createMonzoAccount(): AccountId =
        repositories.importEngine.createAccount(Account(id = AccountId(0), name = "Monzo", openingDate = now), Source.Manual)

    private suspend fun stage(
        fileName: String,
        rows: List<List<String>>,
    ): CsvImport {
        val id =
            repositories.csvImportRepository.createImport(
                fileName = fileName,
                headers = headers,
                rows = rows,
                fileChecksum = "checksum-$fileName",
                fileLastModified = now,
            )
        return reload(id)
    }

    private suspend fun reload(id: CsvImportId): CsvImport = repositories.csvImportRepository.getImport(id).first()!!

    private suspend fun apply(
        import: CsvImport,
        sourceAccount: AccountId,
    ): BulkApplyResult =
        bulkApplyCsv(
            imports = listOf(import),
            sourceAccountOverride = sourceAccount,
            strategies = repositories.csvImportStrategyRepository.getAllStrategies().first(),
            currencies = repositories.currencyRepository.getAllCurrencies().first(),
            accountMappingRepository = repositories.accountMappingRepository,
            accountRepository = repositories.accountRepository,
            csvImportRepository = repositories.csvImportRepository,
            maintenance = maintenance,
            importEngine = repositories.importEngine,
            onProgress = { },
            cryptoRepository = repositories.cryptoRepository,
            attributeAccountMatchers = AttributeAccountMatcher.registry(repositories.accountAttributeRepository.getAll().first()),
        )

    private suspend fun transfersOn(accountId: AccountId): List<Transfer> =
        repositories.transactionRepository.getTransactionsByAccount(accountId).first()

    /** The transfer row [row] of [importId] created. */
    private suspend fun createdBy(
        importId: CsvImportId,
        row: Int,
    ): Transfer {
        val transferId =
            assertNotNull(
                repositories.csvImportRepository
                    .getImportRows(importId, limit = 10, offset = 0)[row]
                    .transferId,
            )
        return repositories.transactionRepository.getTransactionsByIds(listOf(transferId)).getValue(transferId)
    }

    /** Edits [transfer] by hand, as the transaction edit dialog does: a new description and a "note" attribute. */
    private suspend fun editByHand(
        transfer: Transfer,
        description: String,
        note: String,
    ) {
        val noteType = repositories.importEngine.getOrCreateAttributeType("note")
        repositories.importEngine.import(
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
                            attributes = listOf(NewAttribute(noteType, note)),
                        ),
                    ),
            ),
        )
    }

    @Test
    fun reimport_reappliesHandEdits_andReimportsOtherFilesMatchedAgainstIt() =
        runDbTest {
            repositories.currencyRepository.upsertCurrencyByCode("GBP", "British Pound")
            val monzo = createMonzoAccount()
            val first =
                stage(
                    "MonzoDataExport.csv",
                    listOf(row("tx_1", "Coffee Shop", "-3.50", "Coffee"), row("tx_2", "Book Store", "-12.00", "Books")),
                )
            assertEquals(2, apply(first, monzo).transfersCreated)
            val second =
                stage(
                    "MonzoDataExport-later.csv",
                    listOf(row("tx_1", "Coffee Shop", "-3.50", "Coffee"), row("tx_3", "Bakery", "-2.00", "Bread")),
                )
            assertEquals(1, apply(second, monzo).duplicatesSkipped)

            val coffee = createdBy(first.id, row = 0)
            editByHand(coffee, description = "Flat white", note = "work trip")

            val reimports = repositories.reimports(maintenance)
            val planAllProgress = mutableListOf<Progress>()
            val plan = reimports.planAll(listOf(reload(first.id)), onProgress = { planAllProgress += it })
            assertProgressesTo(planAllProgress, "Finding the files that depend on them", "Reading manual edits")
            assertTrue(planAllProgress.any { it.detail.startsWith("File 2 of 2") }, "the file matched against it is read too")
            assertEquals(monzo, plan.imported.getValue(Source.Csv(first.id)).accountId, "the account is read from what the file created")
            assertEquals(setOf<Source>(Source.Csv(second.id)), plan.cascaded, "the second file is re-imported too")
            val edit = plan.manualEdits.single()
            assertEquals("Flat white", edit.description)
            assertEquals(coffee.description, edit.imported.description, "matched on the description the import wrote")
            assertEquals(
                listOf("work trip"),
                edit.attributes.values
                    .single()
                    .map { it.value },
            )
            val changes = edit.describeChanges { "account ${it.id}" }
            assertTrue(changes.contains("Description → \"Flat white\""), "$changes")
            assertTrue(changes.contains("note → work trip"), "$changes")

            assertEquals(3, plan.rerunFiles.sumOf { it.createdTransfers.size }, "both files' transactions are deleted")
            val result = reimports.runPlan(plan)

            assertEquals(3, result.transfersCreated, "the first file's two and the second file's own one")
            assertEquals(1, result.reappliedEdits)
            assertTrue(result.unmatchedEdits.isEmpty())
            assertEquals(1, result.cascadedFiles)
            assertEquals(0, result.cascadedFilesNotRerun)
            val after = transfersOn(monzo)
            assertEquals(3, after.size, "the duplicate row matched the re-created transaction again: $after")
            val recreated = after.single { it.description == "Flat white" }
            assertNotEquals(coffee.id, recreated.id, "the edit is on the replacement")
            assertEquals(listOf("work trip"), recreated.attributes.filter { it.attributeType.name == "note" }.map { it.value })
            val secondRow = repositories.csvImportRepository.getImportRows(second.id, limit = 10, offset = 0).first()
            assertEquals(recreated.id, secondRow.transferId, "the other file's row points at the replacement")

            // The edit is re-applied as a hand edit, so the next re-import keeps it too.
            val again = reimports.planFile(first.id)
            assertEquals("Flat white", again.manualEdits.single().description)
            assertEquals(1, again.cascaded.size, "the re-run duplicate is recorded against the replacement")
        }

    @Test
    fun reimport_reimportsAnotherFileWithTheAccountItWasImportedWith() =
        runDbTest {
            repositories.currencyRepository.upsertCurrencyByCode("GBP", "British Pound")
            val monzo = createMonzoAccount()
            val first =
                stage(
                    "MonzoDataExport.csv",
                    listOf(row("tx_1", "Coffee Shop", "-3.50", "Coffee"), row("tx_2", "Book Store", "-12.00", "Books")),
                )
            assertEquals(2, apply(first, monzo).transfersCreated)
            // One duplicate row: its one transaction has two accounts, so only the recorded account says which is the file's.
            val second = stage("MonzoDataExport-later.csv", listOf(row("tx_1", "Coffee Shop", "-3.50", "Coffee")))
            assertEquals(1, apply(second, monzo).duplicatesSkipped)

            val reimports = repositories.reimports(maintenance)
            val result = reimports.reimportFile(first.id)

            assertEquals(1, result.cascadedFiles)
            assertEquals(0, result.cascadedFilesNotRerun)
            val recreated = createdBy(first.id, row = 0)
            val secondRow = repositories.csvImportRepository.getImportRows(second.id, limit = 10, offset = 0).first()
            assertEquals(recreated.id, secondRow.transferId, "the other file's row points at the replacement")
        }

    @Test
    fun reimport_refusesBeforeDeleting_whenAFileItTakesAlongCanNotBeReimported() =
        runDbTest {
            repositories.currencyRepository.upsertCurrencyByCode("GBP", "British Pound")
            val monzo = createMonzoAccount()
            val first =
                stage(
                    "MonzoDataExport.csv",
                    listOf(row("tx_1", "Coffee Shop", "-3.50", "Coffee"), row("tx_2", "Book Store", "-12.00", "Books")),
                )
            assertEquals(2, apply(first, monzo).transfersCreated)
            val second = stage("MonzoDataExport-later.csv", listOf(row("tx_1", "Coffee Shop", "-3.50", "Coffee")))
            assertEquals(1, apply(second, monzo).duplicatesSkipped)
            // Its latest application records no account, so nothing says which account its row moves money on.
            val secondImport = reload(second.id)
            repositories.importEngine.applyCsvImportMutations(
                listOf(
                    CsvImportMutation.RecordApplication(
                        id = second.id,
                        strategyId = assertNotNull(secondImport.lastAppliedStrategyId),
                        strategyName = assertNotNull(secondImport.lastAppliedStrategyName),
                        appliedAt = assertNotNull(secondImport.lastAppliedAt) + 1.seconds,
                    ),
                ),
            )

            val secondRowBefore = repositories.csvImportRepository.getImportRows(second.id, limit = 10, offset = 0).first()
            val reimports = repositories.reimports(maintenance)
            val plan = reimports.planFile(first.id)
            assertTrue(plan.needsAccount, "the dialog asks which account the other file's row moves money on")
            val refused = assertFailsWith<RerunBlockedException> { reimports.runPlan(plan) }

            assertEquals(listOf("MonzoDataExport-later.csv (nothing says which account it imports onto)"), refused.files)
            val secondRow = repositories.csvImportRepository.getImportRows(second.id, limit = 10, offset = 0).first()
            assertEquals(secondRowBefore.transferId, secondRow.transferId, "nothing was deleted")
            assertNotNull(secondRow.transferId)

            // Given the account, as the dialog asks for it, the other file is re-imported too.
            val result = reimports.runPlan(reimports.planFile(first.id), accountId = monzo)
            assertEquals(1, result.cascadedFiles)
            val relinked = repositories.csvImportRepository.getImportRows(second.id, limit = 10, offset = 0).first()
            assertEquals(createdBy(first.id, row = 0).id, relinked.transferId, "the other file's row points at the replacement")
        }

    @Test
    fun reimport_reportsHandEditsWhoseRowNoLongerCreatesATransaction() =
        runDbTest {
            repositories.currencyRepository.upsertCurrencyByCode("GBP", "British Pound")
            val monzo = createMonzoAccount()
            val file =
                stage(
                    "MonzoDataExport.csv",
                    listOf(row("tx_1", "Coffee Shop", "-3.50", "Coffee"), row("tx_2", "Book Store", "-12.00", "Books")),
                )
            assertEquals(2, apply(file, monzo).transfersCreated)
            val books = createdBy(file.id, row = 1)
            editByHand(books, description = "Novels", note = "gift")

            val reimports = repositories.reimports(maintenance)
            val strategy = assertNotNull(reimports.planFile(file.id).strategies.getValue(Source.Csv(file.id)))
            // The strategy now skips the edited row, so the re-import doesn't create its transaction again.
            val skipBooks =
                StrategyRule(
                    conditions = listOf(Condition("Transaction ID", ConditionOp.EQUALS, "tx_2")),
                    actions = listOf(RuleAction.Skip),
                )
            repositories.importEngine.updateCsvStrategy(
                strategy.copy(
                    config =
                        strategy.config.copy(
                            rules =
                                listOf(skipBooks) + strategy.config.rules,
                        ),
                ),
            )

            val result = reimports.reimportFile(file.id)

            assertEquals(0, result.reappliedEdits)
            assertEquals("Novels", result.unmatchedEdits.single().description)
            assertEquals(1, transfersOn(monzo).size, "only the unskipped row is imported")
        }

    // Planning reports each named step on a bar that never moves backwards.
    @Test
    fun reimportAll_reportsEachPhaseAsItsOwnSweepOfTheBar() =
        runDbTest {
            repositories.currencyRepository.upsertCurrencyByCode("GBP", "British Pound")
            val monzo = createMonzoAccount()
            val first = stage("MonzoDataExport.csv", listOf(row("tx_1", "Coffee Shop", "-3.50", "Coffee")))
            apply(first, monzo)
            val second = stage("MonzoDataExport-later.csv", listOf(row("tx_2", "Bakery", "-2.00", "Bread")))
            apply(second, monzo)

            val reimports = repositories.reimports(maintenance)
            val progress = mutableListOf<Progress>()
            reimports.reimportAll(
                reimports.planAll(listOf(reload(first.id), reload(second.id))),
                accountId = null,
                currencyId = null,
                onProgress = { progress += it },
            )

            // The delete batch, preparing the files and re-importing them each fill the bar from empty, in that order.
            assertEquals(
                listOf("Removing what the files created", "Preparing files", "Re-importing files"),
                progress.mapNotNull { it.phase }.map { it.name }.distinct(),
            )
            assertTrue(progress.all { it.phase?.count == 3 }, "every emission names its phase of three: $progress")
            (1..3).forEach { number ->
                val phase = progress.filter { it.phase?.number == number }
                assertProgressesTo(phase)
                assertEquals(1f, phase.last().fraction, "phase $number fills the bar: $phase")
            }
            val files = progress.filter { it.phase?.number == 3 }
            assertEquals(0f, files.first().fraction, "the files start the bar again: $files")
            assertProgressesTo(files, "Re-applying manual edits", "Refreshing views")
            assertEquals(1f, files.last().fraction, "the steps after the last file keep the files' bar full")
        }

    private fun assertProgressesTo(
        progress: List<Progress>,
        vararg details: String,
    ) {
        details.forEach { detail -> assertTrue(progress.any { it.detail == detail }, "no \"$detail\" in $progress") }
        val fractions = progress.mapNotNull { it.fraction }
        assertEquals(fractions.sorted(), fractions, "the bar never moves backwards: $progress")
    }
}
