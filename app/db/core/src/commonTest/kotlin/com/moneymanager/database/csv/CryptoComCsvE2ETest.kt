@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.csv

import com.moneymanager.csvimporter.BulkImportProgress
import com.moneymanager.csvimporter.CsvBulkResult
import com.moneymanager.csvimporter.bulkApplyCsv
import com.moneymanager.database.assertBulkProgress
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * End-to-end test for the crypto.com CSV imports. All three crypto.com exports share one column
 * header, so this drives the full pipeline over a staged card_* file, fiat_* file and crypto_* file:
 * filename/content selection must route the card and fiat files to their strategies and skip the
 * crypto file, the fiat Transaction Kind semantics must produce the right directions, and the card
 * top-up recorded by BOTH files must be reconciled (imported excluded + linked) rather than counted
 * twice.
 */
class CryptoComCsvE2ETest : DbTest() {
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

    @Suppress("LongParameterList")
    private fun row(
        timestamp: String,
        description: String,
        currency: String,
        amount: String,
        toCurrency: String,
        toAmount: String,
        nativeAmount: String,
        kind: String,
    ): List<String> = listOf(timestamp, description, currency, amount, toCurrency, toAmount, "GBP", nativeAmount, "0.0", kind, "")

    // The same top-up appears in both files: "Top Up Card" in the fiat export and "GBP Deposit" in
    // the card export, a minute apart.
    private val fiatRows =
        listOf(
            row("2023-11-17 11:18:14", "GBP Deposit (via FPS)", "GBP", "2000.0", "GBP", "2000.0", "2000.0", "viban_deposit"),
            row("2023-11-19 20:03:12", "Top Up Card", "GBP", "400.0", "GBP", "400.0", "400.0", "viban_card_top_up"),
            row("2023-11-20 09:00:00", "GBP -> TGBP", "GBP", "5000.0", "TGBP", "5000.0", "5000.0", "viban_purchase"),
            row("2023-11-21 10:00:00", "TGBP -> GBP", "TGBP", "5009.86", "GBP", "5009.86", "5009.86", "crypto_viban"),
            row("2023-11-22 11:00:00", "GBP Withdrawal (via FPS)", "GBP", "-5055.89", "GBP", "-5055.89", "-5055.89", "viban_withdrawal"),
        )
    private val cardRows =
        listOf(
            row("2023-11-19 20:04:29", "GBP Deposit", "GBP", "400.0", "", "", "400.0", ""),
            row("2023-11-19 21:15:00", "Spotify P3 C6 Ef4945", "GBP", "-12.99", "", "", "-12.99", ""),
        )
    private val cryptoRows =
        listOf(
            row("2023-11-19 08:00:00", "Card Cashback", "CRO", "0.37", "", "", "0.09", "referral_card_cashback"),
            row("2023-11-19 10:00:00", "Card Cashback", "CRO", "0.42", "", "", "0.10", "referral_card_cashback"),
        )

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

    private suspend fun applyAll(imports: List<CsvImport>): CsvBulkResult {
        val progress = mutableListOf<BulkImportProgress>()
        val result =
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
                onProgress = { progress += it },
                cryptoRepository = repositories.cryptoRepository,
            )
        assertBulkProgress(progress, imports.size)
        return result
    }

    @Test
    fun importAll_routesFilesByName_mapsKinds_andReconcilesTheSharedTopUp() =
        runTest {
            val card = stage("card_transactions_record_20231120_210200.csv", cardRows)
            val fiat = stage("fiat_transactions_record_20231120_085814.csv", fiatRows)
            val crypto = stage("crypto_transactions_record_20231119_121744.csv", cryptoRows)

            val result = applyAll(listOf(card, fiat, crypto))

            // All three files match their strategies by filename: the crypto_* export is now imported
            // by the "Crypto.com Crypto" strategy (denominating in the real crypto currency).
            assertEquals(3, result.filesImported, "card + fiat + crypto should import")
            assertEquals(0, result.filesSkippedNoStrategy)
            assertEquals(0, result.filesFailed)

            val accounts = repositories.accountRepository.getAllAccounts().first()
            val accountNames = accounts.map { it.name }.toSet()
            assertEquals(
                setOf(
                    "Crypto.com Card",
                    "Crypto.com Cash",
                    "GBP Deposit (via FPS)",
                    "GBP Withdrawal (via FPS)",
                    "Spotify P3 C6 Ef4945",
                    // The crypto reward counterparty, plus the single account that holds ALL crypto
                    // (CRO cashback + TGBP conversions) as separate per-asset balances.
                    "Card Cashback",
                    "Crypto.com",
                ),
                accountNames,
            )

            repositories.maintenanceService.refreshMaterializedViews()
            val cryptoAccountId = accounts.first { it.name == "Crypto.com" }.id
            val cashId = accounts.first { it.name == "Crypto.com Cash" }.id
            val cardId = accounts.first { it.name == "Crypto.com Card" }.id
            val depositId = accounts.first { it.name == "GBP Deposit (via FPS)" }.id
            val withdrawalId = accounts.first { it.name == "GBP Withdrawal (via FPS)" }.id

            // One "Crypto.com" account holds every crypto as a separate per-asset balance.
            val cryptoBalances =
                repositories.transactionRepository
                    .getAccountBalances()
                    .first()
                    .filter { it.accountId == cryptoAccountId }
                    .associate { it.balance.asset.code to it.balance.toDisplayValue().toString() }
            assertEquals("0.79", cryptoBalances["CRO"], "CRO cashback 0.37 + 0.42, created on demand")
            // TGBP is an unknown ticker created on demand as crypto; net of the two conversions.
            assertEquals("-9.86", cryptoBalances["TGBP"], "TGBP bought 5000 - sold 5009.86")

            // The two viban conversions are cross-asset trades (not GBP wallet transfers), correctly directed.
            val cryptoTrades = repositories.tradeRepository.getTradesByAccount(cryptoAccountId).first()
            assertTrue(
                cryptoTrades.any {
                    it.fromAccountId == cashId &&
                        it.from.asset.code == "GBP" &&
                        it.toAccountId == cryptoAccountId &&
                        it.to.asset.code == "TGBP"
                },
                "viban_purchase: Cash GBP -> Crypto.com TGBP",
            )
            assertTrue(
                cryptoTrades.any {
                    it.fromAccountId == cryptoAccountId &&
                        it.from.asset.code == "TGBP" &&
                        it.toAccountId == cashId &&
                        it.to.asset.code == "GBP"
                },
                "crypto_viban: Crypto.com TGBP -> Cash GBP",
            )

            // Amounts in GBP minor units (scale factor 100).
            fun Money.pence(): Long = amount.toLong()

            val cashTransfers = repositories.transactionRepository.getTransactionsByAccount(cashId).first()
            // Directions per Transaction Kind (the sign alone does not encode them).
            assertTrue(
                cashTransfers.any { it.sourceAccountId == depositId && it.targetAccountId == cashId },
                "viban_deposit: external -> Cash",
            )
            assertTrue(
                cashTransfers.any { it.sourceAccountId == cashId && it.targetAccountId == withdrawalId },
                "viban_withdrawal: Cash -> external",
            )

            // The shared top-up: both records exist as Cash -> Card...
            val topUps =
                cashTransfers.filter {
                    it.sourceAccountId == cashId && it.targetAccountId == cardId && it.amount.pence() == 40_000L
                }
            assertEquals(2, topUps.size, "both files' top-up records are kept")

            // ...but exactly one carries the excluded attribute and a reconciled link to the other,
            // so the movement counts once.
            val excluded = topUps.filter { t -> t.attributes.any { it.attributeType.name == "excluded" && it.value == "reconciled" } }
            assertEquals(1, excluded.size, "exactly one top-up record is excluded as reconciled")
            val original = topUps.single { it.id != excluded.single().id }
            val relationships = repositories.transferRelationshipRepository.getByTransfer(excluded.single().id).first()
            val reconciledLink = relationships.single { it.relationshipType.name == "reconciled" }
            assertEquals(excluded.single().id, reconciledLink.id1)
            assertEquals(original.id, reconciledLink.id2)
        }

    @Test
    fun reimportingBothFiles_producesOnlyDuplicates() =
        runTest {
            val card = stage("card_transactions_record_20231120_210200.csv", cardRows)
            val fiat = stage("fiat_transactions_record_20231120_085814.csv", fiatRows)
            applyAll(listOf(card, fiat))

            suspend fun allTransfers(): List<Transfer> =
                repositories.transactionRepository
                    .getTransactionsByDateRange(
                        startDate = Instant.parse("2023-01-01T00:00:00Z"),
                        endDate = Instant.parse("2024-12-31T00:00:00Z"),
                    ).first()
            val transfersAfterFirst = allTransfers().size

            // Re-staging the same content under new names must not re-import or re-reconcile anything:
            // exact matches win before the reconcile pass.
            val cardAgain = stage("card_transactions_record_20240101_000000.csv", cardRows)
            val fiatAgain = stage("fiat_transactions_record_20240101_000000.csv", fiatRows)
            val second = applyAll(listOf(cardAgain, fiatAgain))

            assertEquals(0, second.filesFailed, "re-import must not fail")
            assertEquals(0, second.transfersCreated, "everything is a duplicate on re-import")
            assertEquals(transfersAfterFirst, allTransfers().size)
        }

    @Test
    fun importAll_marksHeaderOnlyFileAsImported() =
        runTest {
            // A header-only export (no transactions in that period) still matches its strategy by
            // filename. It has nothing to import, but must be marked imported so it leaves the
            // Unimported tab instead of reappearing on every "Import all".
            val emptyFiat = stage("fiat_transactions_record_20231120_085814.csv", emptyList())

            val result = applyAll(listOf(emptyFiat))

            assertEquals(1, result.filesImported, "the empty file is counted as imported")
            assertEquals(0, result.transfersCreated, "nothing to import")
            assertEquals(0, result.filesSkippedNoStrategy)
            assertEquals(0, result.filesFailed)
            val applied = repositories.csvImportRepository.getImport(emptyFiat.id).first()!!
            assertTrue(
                applied.lastAppliedAt != null,
                "the empty file has an application record (out of the Unimported tab)",
            )

            // Idempotent: once applied, a second "Import all" is a no-op — it neither re-records nor
            // re-imports, and the file stays imported.
            val second = applyAll(listOf(applied))
            assertEquals(0, second.filesImported, "already-applied empty file is not re-recorded")
            assertEquals(0, second.transfersCreated)
            assertEquals(0, second.filesFailed)
            val stillApplied = repositories.csvImportRepository.getImport(emptyFiat.id).first()!!
            assertTrue(
                stillApplied.lastAppliedAt != null,
                "the empty file stays imported after a second run",
            )
        }

    @Test
    fun fiatExport_cryptoBuy_importsAsTrade() =
        runTest {
            // A real crypto buy in the fiat export: £100 -> 0.005 BTC (viban_purchase, To Currency = BTC).
            val fiat =
                stage(
                    "fiat_transactions_record_20231120_085814.csv",
                    listOf(row("2023-11-20 09:00:00", "GBP -> BTC", "GBP", "100.0", "BTC", "0.005", "100.0", "viban_purchase")),
                )

            assertEquals(1, applyAll(listOf(fiat)).filesImported)
            repositories.maintenanceService.refreshMaterializedViews()

            val accounts = repositories.accountRepository.getAllAccounts().first()
            val cashId = accounts.first { it.name == "Crypto.com Cash" }.id
            // All crypto lands in the single "Crypto.com" account, not a per-ticker wallet.
            val cryptoAccountId = accounts.first { it.name == "Crypto.com" }.id

            // The conversion became a trade (GBP out of Cash, BTC into the crypto account), not a GBP transfer.
            val trade =
                repositories.tradeRepository
                    .getTradesByAccount(cryptoAccountId)
                    .first()
                    .single()
            assertEquals(cashId, trade.fromAccountId)
            assertEquals("GBP", trade.from.asset.code)
            assertEquals("100", trade.from.toDisplayValue().toString())
            assertEquals("BTC", trade.to.asset.code)
            assertEquals("0.005", trade.to.toDisplayValue().toString())

            val balances = repositories.transactionRepository.getAccountBalances().first()
            assertEquals(
                "0.005",
                balances
                    .first { it.accountId == cryptoAccountId && it.balance.asset.code == "BTC" }
                    .balance
                    .toDisplayValue()
                    .toString(),
            )
            assertEquals(
                "-100",
                balances
                    .first { it.accountId == cashId }
                    .balance
                    .toDisplayValue()
                    .toString(),
            )
        }
}
