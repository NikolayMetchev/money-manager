package com.moneymanager.database.csv

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.csvimporter.AttributeAccountMatcher
import com.moneymanager.csvimporter.BulkApplyResult
import com.moneymanager.csvimporter.CsvUnimportResult
import com.moneymanager.csvimporter.bulkApplyCsv
import com.moneymanager.csvimporter.executeCsvBulkUnimport
import com.moneymanager.csvimporter.executeCsvUnimport
import com.moneymanager.csvimporter.planCsvBulkUnimport
import com.moneymanager.csvimporter.planCsvUnimport
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csv.CsvImportHistoryEvent
import com.moneymanager.importengineapi.createAccount
import com.moneymanager.importengineapi.setCsvImportIgnored
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.reimports
import com.moneymanager.test.database.upsertCurrencyByCode
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * Unimporting a CSV file withdraws its records: it deletes what nothing else still observes, resets it, moves it to the Ignored tab and leaves an "unimported" entry in its
 * application audit trail.
 */
class CsvUnimportE2ETest : DbTest() {
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

    private suspend fun createAccount(name: String): AccountId =
        repositories.importEngine.createAccount(Account(id = AccountId(0), name = name, openingDate = now), Source.Manual)

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
            attributeAccountMatchers = attributeMatchers(),
        )

    private suspend fun attributeMatchers() = AttributeAccountMatcher.registry(repositories.accountAttributeRepository.getAll().first())

    private suspend fun unimport(import: CsvImport): CsvUnimportResult =
        executeCsvUnimport(
            plan = planCsvUnimport(import.id, repositories.csvImportRepository, repositories.transferSourceRepository),
            accountRepository = repositories.accountRepository,
            transactionRepository = repositories.transactionRepository,
            relationshipRepository = repositories.relationshipRepository,
            tradeRepository = repositories.tradeRepository,
            maintenance = maintenance,
            importEngine = repositories.importEngine,
        )

    private suspend fun transfersOn(accountId: AccountId): List<Transfer> =
        repositories.transactionRepository.getTransactionsByAccount(accountId).first()

    private suspend fun balanceOf(accountId: AccountId): BigDecimal {
        repositories.maintenanceService.refreshMaterializedViews()
        return repositories.transactionRepository
            .getAccountBalances()
            .first()
            .single { it.accountId == accountId }
            .balance
            .toDisplayValue()
    }

    private fun Transfer.isReconcileExcluded(): Boolean = attributes.any { it.attributeType.name == "excluded" && it.value == "reconciled" }

    @Test
    fun unimport_deletesCreatedTransfersAndAccounts_andResetsTheFile() =
        runDbTest {
            repositories.currencyRepository.upsertCurrencyByCode("GBP", "British Pound")
            val monzo = createAccount("Monzo")
            val file =
                stage(
                    "MonzoDataExport.csv",
                    listOf(row("tx_1", "Coffee Shop", "-3.50", "Coffee"), row("tx_2", "Book Store", "-12.00", "Books")),
                )
            assertEquals(2, apply(file, monzo).transfersCreated)
            val createdAccounts = repositories.csvImportRepository.getAccountsCreatedByImport(file.id)
            assertTrue(createdAccounts.isNotEmpty(), "the import auto-creates its counterparty accounts")

            val result = unimport(reload(file.id))

            assertEquals(2, result.deletedTransfers)
            assertTrue(transfersOn(monzo).isEmpty(), "every transfer the file created is gone")
            val remainingAccounts =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .map { it.id }
            assertTrue(createdAccounts.none { it in remainingAccounts }, "emptied import-created accounts are deleted")
            assertTrue(monzo in remainingAccounts, "a manually created account is never deleted")

            val after = reload(file.id)
            assertTrue(after.ignored, "an unimported file moves to the Ignored tab")
            assertNull(after.lastAppliedAt)
            assertEquals(0, after.applicationCount)
            assertNotNull(after.lastUnimportedAt)
            val rows = repositories.csvImportRepository.getImportRows(file.id, limit = 10, offset = 0)
            assertTrue(rows.all { it.importStatus == null && it.transferId == null }, "every row is back to never-imported")

            val history = repositories.auditRepository.getCsvImportHistory(file.id)
            assertEquals(2, history.size, "one application, then one unimport: $history")
            assertIs<CsvImportHistoryEvent.Unimported>(history[0])
            assertIs<CsvImportHistoryEvent.Applied>(history[1])

            // Restored, the file imports cleanly again.
            repositories.importEngine.setCsvImportIgnored(file.id, ignored = false)
            assertEquals(2, apply(reload(file.id), monzo).transfersCreated)
            assertEquals(2, transfersOn(monzo).size)
        }

    // Unimport withdraws only the file's own observations: a transfer another file's row still records stays, counted
    // from that file, and nothing of that file is reset.
    @Test
    fun unimport_keepsATransferAnotherFileStillRecords() =
        runDbTest {
            repositories.currencyRepository.upsertCurrencyByCode("GBP", "British Pound")
            val monzo = createAccount("Monzo")
            val first = stage("MonzoDataExport.csv", listOf(row("tx_1", "Coffee Shop", "-3.50", "Coffee")))
            assertEquals(1, apply(first, monzo).transfersCreated)
            val second = stage("MonzoDataExport-again.csv", listOf(row("tx_1", "Coffee Shop", "-3.50", "Coffee")))
            assertEquals(1, apply(second, monzo).duplicatesSkipped, "the second file's row is a duplicate of the first's")
            val secondRowsBefore = repositories.csvImportRepository.getImportRows(second.id, limit = 10, offset = 0)

            val plan = planCsvUnimport(first.id, repositories.csvImportRepository, repositories.transferSourceRepository)
            assertTrue(plan.transferIds.isEmpty() && plan.affectedFiles.isEmpty(), "$plan")
            assertEquals(0, unimport(reload(first.id)).deletedTransfers)

            assertEquals(1, transfersOn(monzo).size, "the second file still records the transfer")
            assertEquals(0, balanceOf(monzo).compareTo(BigDecimal("-3.50")))
            val secondRows = repositories.csvImportRepository.getImportRows(second.id, limit = 10, offset = 0)
            assertEquals(secondRowsBefore, secondRows, "nothing of the second file is reset")

            // Once its last record goes, so does the transfer.
            assertEquals(1, unimport(reload(second.id)).deletedTransfers)
            assertTrue(transfersOn(monzo).isEmpty())
        }

    // Unimporting one of two exports of one movement leaves it counted from the other export, as a fresh import of only
    // that export would; unimporting that one too removes it.
    @Test
    fun unimport_keepsTheMovementTheOtherExportRecords() =
        runDbTest {
            repositories.currencyRepository.upsertCurrencyByCode("GBP", "British Pound")
            val personal = createAccount("Monzo")
            val joint = createAccount("Monzo Joint")
            val personalExport = stage("MonzoDataExport_personal.csv", listOf(row("tx_personal_1", "Monzo Joint", "-1000.00", "To Joint")))
            apply(personalExport, personal)
            val jointExport = stage("MonzoDataExport_joint.csv", listOf(row("tx_joint_1", "Monzo", "1000.00", "From Monzo")))
            apply(jointExport, joint)
            assertTrue(transfersOn(personal).single().let { !it.isReconcileExcluded() }, "precondition: one counted transfer")
            val jointRowsBefore = repositories.csvImportRepository.getImportRows(jointExport.id, limit = 10, offset = 0)

            val plan = planCsvUnimport(personalExport.id, repositories.csvImportRepository, repositories.transferSourceRepository)
            assertTrue(plan.transferIds.isEmpty() && plan.affectedFiles.isEmpty(), "$plan")
            unimport(reload(personalExport.id))

            val kept = transfersOn(joint)
            assertEquals(1, kept.size, "$kept")
            assertTrue(kept.none { it.isReconcileExcluded() } && kept.single().sourceAccountId == personal, "$kept")
            assertEquals(0, balanceOf(personal).compareTo(BigDecimal("-1000")))
            assertEquals(0, balanceOf(joint).compareTo(BigDecimal("1000")))
            val jointRows = repositories.csvImportRepository.getImportRows(jointExport.id, limit = 10, offset = 0)
            assertEquals(jointRowsBefore, jointRows, "the joint export's row is untouched")

            assertEquals(1, unimport(reload(jointExport.id)).deletedTransfers)
            assertTrue(transfersOn(joint).isEmpty() && transfersOn(personal).isEmpty())
        }

    @Test
    fun unimportAll_removesEveryFile_withoutWarningAboutFilesInTheSet() =
        runDbTest {
            repositories.currencyRepository.upsertCurrencyByCode("GBP", "British Pound")
            val monzo = createAccount("Monzo")
            val first = stage("MonzoDataExport.csv", listOf(row("tx_1", "Coffee Shop", "-3.50", "Coffee")))
            apply(first, monzo)
            val second =
                stage(
                    "MonzoDataExport-later.csv",
                    listOf(row("tx_1", "Coffee Shop", "-3.50", "Coffee"), row("tx_2", "Book Store", "-12.00", "Books")),
                )
            assertEquals(1, apply(second, monzo).transfersCreated, "tx_1 is a duplicate of the first file's row")

            val plan =
                planCsvBulkUnimport(listOf(first.id, second.id), repositories.csvImportRepository, repositories.transferSourceRepository)
            assertTrue(plan.affectedFiles.isEmpty(), "the dependent file is being unimported too: ${plan.affectedFiles}")
            assertEquals(2, plan.transferCount)

            val result =
                executeCsvBulkUnimport(
                    plan = plan,
                    csvImportRepository = repositories.csvImportRepository,
                    transferSourceRepository = repositories.transferSourceRepository,
                    accountRepository = repositories.accountRepository,
                    transactionRepository = repositories.transactionRepository,
                    relationshipRepository = repositories.relationshipRepository,
                    tradeRepository = repositories.tradeRepository,
                    maintenance = maintenance,
                    importEngine = repositories.importEngine,
                )

            assertEquals(2, result.deletedTransfers)
            assertTrue(result.affectedFiles.isEmpty())
            assertTrue(transfersOn(monzo).isEmpty())
            listOf(first.id, second.id).forEach { id ->
                val after = reload(id)
                assertTrue(after.ignored && after.lastAppliedAt == null && after.lastUnimportedAt != null, "$after")
            }
        }
}
