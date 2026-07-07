@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.csv

import com.moneymanager.csvimporter.bulkApplyCsv
import com.moneymanager.csvimporter.bulkReimportCsv
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * The crypto.com crypto_transactions_record_* export (previously skipped) is now imported by the
 * built-in "Crypto.com Crypto" strategy, which denominates each row in its real Currency/Amount and
 * creates the crypto asset on demand — so a wallet holds a genuine crypto balance (0.37 CRO), not a
 * GBP figure with the crypto amount buried in an attribute.
 */
class CryptoComCryptoE2ETest : DbTest() {
    override val installBuiltInStrategies: Boolean = true

    private val now = Clock.System.now()
    private val headers =
        listOf(
            "Timestamp (UTC)",
            "Transaction Description",
            "Currency",
            "Amount",
            "To Currency",
            "To Amount",
            "Native Currency",
            "Native Amount",
            "Native Amount (in USD)",
            "Transaction Kind",
            "Transaction Hash",
        )

    private val maintenance =
        object : Maintenance {
            override suspend fun reindex(): Duration = Duration.ZERO

            override suspend fun vacuum(): Duration = Duration.ZERO

            override suspend fun analyze(): Duration = Duration.ZERO

            override suspend fun refreshMaterializedViews(): Duration = Duration.ZERO

            override suspend fun fullRefreshMaterializedViews(): Duration = Duration.ZERO
        }

    @Suppress("LongParameterList")
    private fun row(
        timestamp: String,
        description: String,
        currency: String,
        amount: String,
        nativeAmount: String,
        kind: String,
    ): List<String> = listOf(timestamp, description, currency, amount, "", "", "GBP", nativeAmount, "0.0", kind, "")

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
        return repositories.csvImportRepository.getImport(id).first()!!
    }

    private suspend fun applyAll(imports: List<CsvImport>) =
        bulkApplyCsv(
            imports = imports,
            sourceAccountOverride = null,
            strategies = repositories.csvImportStrategyRepository.getAllStrategies().first(),
            currencies = repositories.currencyRepository.getAllCurrencies().first(),
            accountMappingRepository = repositories.accountMappingRepository,
            accountRepository = repositories.accountRepository,
            csvImportRepository = repositories.csvImportRepository,
            maintenance = maintenance,
            importEngine = repositories.importEngine,
            onProgress = { _, _ -> },
            cryptoRepository = repositories.cryptoRepository,
        )

    private suspend fun reimportAll(imports: List<CsvImport>) =
        bulkReimportCsv(
            imports = imports,
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
            onProgress = { _, _ -> },
            cryptoRepository = repositories.cryptoRepository,
        )

    @Test
    fun cryptoExport_reimportAll_resolvesCryptoAndKeepsBalance() =
        runTest {
            val file =
                stage(
                    "crypto_transactions_record_20231119_121744.csv",
                    listOf(row("2023-11-19 08:00:00", "Card Cashback", "CRO", "0.37", "0.09", "referral_card_cashback")),
                )
            applyAll(listOf(file))
            // Re-import all: the crypto asset must still resolve (no "Currency not found") and the CRO
            // balance must be unchanged.
            reimportAll(repositories.csvImportRepository.getAllImports().first())

            repositories.maintenanceService.refreshMaterializedViews()
            val wallet =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .first { it.name == "Crypto.com" }
            val balance =
                repositories.transactionRepository
                    .getAccountBalances()
                    .first()
                    .first { it.accountId == wallet.id }
                    .balance
            assertEquals("0.37", balance.toDisplayValue().toString())
        }

    @Test
    fun cryptoExport_importsCashbackAsRealCryptoBalance() =
        runTest {
            val crypto =
                stage(
                    "crypto_transactions_record_20231119_121744.csv",
                    listOf(
                        row("2023-11-19 08:00:00", "Card Cashback", "CRO", "0.37", "0.09", "referral_card_cashback"),
                        row("2023-11-20 08:00:00", "Card Cashback", "CRO", "0.42", "0.10", "referral_card_cashback"),
                    ),
                )

            val result = applyAll(listOf(crypto))
            assertEquals(1, result.filesImported, "crypto_* file now imports (no longer skipped)")
            assertEquals(0, result.filesSkippedNoStrategy)

            // CRO asset created on demand.
            val cro = repositories.cryptoRepository.getCryptoAssetByCode("CRO").first()
            assertNotNull(cro, "CRO crypto asset created on demand")
            assertEquals("Cronos", cro.name)

            // The wallet holds a real crypto balance: 0.37 + 0.42 = 0.79 CRO.
            repositories.maintenanceService.refreshMaterializedViews()
            val accounts = repositories.accountRepository.getAllAccounts().first()
            val wallet = accounts.first { it.name == "Crypto.com" }
            val balance =
                repositories.transactionRepository
                    .getAccountBalances()
                    .first()
                    .first { it.accountId == wallet.id }
                    .balance
            assertEquals("CRO", balance.currency.code)
            assertEquals("0.79", balance.toDisplayValue().toString())
        }
}
