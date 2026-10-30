package com.moneymanager.database.csv

import com.moneymanager.csvimporter.AttributeAccountMatcher
import com.moneymanager.csvimporter.bulkApplyCsv
import com.moneymanager.csvimporter.importedSettings
import com.moneymanager.database.assertBulkProgress
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.importengineapi.createAccount
import com.moneymanager.tasks.Progress
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.reimports
import com.moneymanager.test.database.upsertCurrencyByCode
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * Monzo CSV has no SOURCE_ACCOUNT mapping, so a fresh import needs a user-chosen source account. Once
 * a file has imported, though, its application records that account, so re-import uses it rather than
 * asking again ([importedSettings]).
 */
class CsvReimportHistoricalSourceAccountE2ETest : DbTest() {
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

    // Imports [rows] as a Monzo export into a new "Monzo" account, as "Import all" with that account chosen does.
    private suspend fun importMonzo(
        rows: List<List<String>>,
        checksum: String,
    ): Pair<AccountId, CsvImport> {
        repositories.currencyRepository.upsertCurrencyByCode("GBP", "British Pound")
        val monzoAccount =
            repositories.importEngine.createAccount(
                Account(id = AccountId(0), name = "Monzo", openingDate = now),
                Source.Manual,
            )
        val id =
            repositories.csvImportRepository.createImport(
                fileName = "MonzoDataExport.csv",
                headers = headers,
                rows = rows,
                fileChecksum = checksum,
                fileLastModified = now,
            )
        val staged = repositories.csvImportRepository.getImport(id).first()!!

        val progress = mutableListOf<Progress>()
        bulkApplyCsv(
            imports = listOf(staged),
            sourceAccountOverride = monzoAccount,
            strategies = repositories.csvImportStrategyRepository.getAllStrategies().first(),
            currencies = repositories.currencyRepository.getAllCurrencies().first(),
            accountMappingRepository = repositories.accountMappingRepository,
            accountRepository = repositories.accountRepository,
            csvImportRepository = repositories.csvImportRepository,
            maintenance = maintenance,
            importEngine = repositories.importEngine,
            onProgress = { progress += it },
            cryptoRepository = repositories.cryptoRepository,
            attributeAccountMatchers = AttributeAccountMatcher.registry(repositories.accountAttributeRepository.getAll().first()),
        )
        assertBulkProgress(progress, 1)
        return monzoAccount to repositories.csvImportRepository.getImport(id).first()!!
    }

    @Test
    fun reimportAll_resolvesSourceAccountFromHistory_withNoOverride() =
        runDbTest {
            val (monzoAccount, imported) =
                importMonzo(
                    listOf(row("tx_1", "Coffee Shop", "-3.50", "Coffee"), row("tx_2", "Book Store", "-12.00", "Books")),
                    checksum = "checksum-monzo-hist",
                )
            assertEquals(monzoAccount, imported.importedSettings.accountId, "The import should record the Monzo account")

            // Re-import with NO source account override at all — this is the bug: it must not need one.
            val reimports = repositories.reimports(maintenance)
            val reimportResult =
                reimports.reimportAll(
                    reimports.planAll(listOf(imported)),
                    accountId = null,
                    currencyId = null,
                    onProgress = { },
                )
            assertEquals(1, reimportResult.filesImported)

            val transfers = repositories.transactionRepository.getTransactionsByAccount(monzoAccount).first()
            assertEquals(2, transfers.size, "The transfers should still be on the Monzo account after re-import")
        }

    // Every transfer of a one-row file shares both of its accounts, so no account can be read off the
    // transfers; the recorded one is what keeps the re-import from asking.
    @Test
    fun reimport_ofSingleRowFile_needsNoSourceAccount() =
        runDbTest {
            val (monzoAccount, imported) =
                importMonzo(listOf(row("tx_1", "Coffee Shop", "-3.50", "Coffee")), checksum = "checksum-monzo-single")

            val reimports = repositories.reimports(maintenance)
            val plan = reimports.plan(imported)
            assertFalse(plan.needsAccount, "A re-import should use the account the file was imported with")

            reimports.reimport(plan, accountId = null, currencyId = null)
            val transfers = repositories.transactionRepository.getTransactionsByAccount(monzoAccount).first()
            assertEquals(1, transfers.size, "The transfer should still be on the Monzo account after re-import")
            val reimported = repositories.csvImportRepository.getImport(imported.id).first()!!
            assertEquals(monzoAccount, reimported.importedSettings.accountId, "The re-import should record the account again")
        }
}
