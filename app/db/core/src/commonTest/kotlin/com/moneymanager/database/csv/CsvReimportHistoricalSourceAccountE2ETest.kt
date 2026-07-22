@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.csv

import com.moneymanager.csvimporter.AttributeAccountMatcher
import com.moneymanager.csvimporter.BulkImportProgress
import com.moneymanager.csvimporter.bulkApplyCsv
import com.moneymanager.csvimporter.bulkReimportCsv
import com.moneymanager.database.assertBulkProgress
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.importengineapi.createAccount
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.upsertCurrencyByCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * Monzo CSV has no SOURCE_ACCOUNT mapping, so a fresh import needs a user-chosen source account. Once
 * a file has imported successfully, though, its source account is a recoverable fact — the account
 * appearing on every one of its created transfers — so re-import must derive it automatically rather
 * than asking again ([CsvImportReadRepository.historicalSourceAccounts]).
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

    @Test
    fun reimportAll_resolvesSourceAccountFromHistory_withNoOverride() =
        runTest {
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
                    rows =
                        listOf(
                            row("tx_1", "Coffee Shop", "-3.50", "Coffee"),
                            row("tx_2", "Book Store", "-12.00", "Books"),
                        ),
                    fileChecksum = "checksum-monzo-hist",
                    fileLastModified = now,
                )
            val staged = repositories.csvImportRepository.getImport(id).first()!!

            val progress = mutableListOf<BulkImportProgress>()
            val attributeMatchers =
                AttributeAccountMatcher.registry(repositories.accountAttributeRepository.getAll().first())
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
                attributeAccountMatchers = attributeMatchers,
            )
            assertBulkProgress(progress, 1)

            val imported: CsvImport = repositories.csvImportRepository.getImport(id).first()!!
            val historical = repositories.csvImportRepository.historicalSourceAccounts()
            assertEquals(monzoAccount, historical[imported.id], "The Monzo account should be recoverable from history")

            // Re-import with NO source account override at all — this is the bug: it must not need one.
            val reimportResult =
                bulkReimportCsv(
                    imports = listOf(imported),
                    sourceAccountOverride = null,
                    strategies = repositories.csvImportStrategyRepository.getAllStrategies().first(),
                    currencies = repositories.currencyRepository.getAllCurrencies().first(),
                    accountMappingRepository = repositories.accountMappingRepository,
                    accountRepository = repositories.accountRepository,
                    csvImportRepository = repositories.csvImportRepository,
                    transactionRepository = repositories.transactionRepository,
                    relationshipRepository = repositories.transferRelationshipRepository,
                    transferSourceRepository = repositories.transferSourceRepository,
                    maintenance = maintenance,
                    importEngine = repositories.importEngine,
                    onProgress = { },
                    cryptoRepository = repositories.cryptoRepository,
                    tradeRepository = repositories.tradeRepository,
                    attributeAccountMatchers = attributeMatchers,
                )
            assertEquals(1, reimportResult.filesImported)

            val transfers = repositories.transactionRepository.getTransactionsByAccount(monzoAccount).first()
            assertEquals(2, transfers.size, "The transfers should still be on the Monzo account after re-import")
        }
}
