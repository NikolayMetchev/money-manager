@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.csv

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.csvimporter.BulkImportProgress
import com.moneymanager.csvimporter.CsvBulkResult
import com.moneymanager.csvimporter.CsvReimportResult
import com.moneymanager.csvimporter.bulkApplyCsv
import com.moneymanager.csvimporter.executeCsvReimport
import com.moneymanager.csvimporter.planCsvReimport
import com.moneymanager.database.assertBulkProgress
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.importengineapi.createAccount
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.upsertCurrencyByCode
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
                    // The card export says a top-up arrived but not from which wallet, so it books the
                    // movement against this placeholder rather than guessing the Cash wallet.
                    "Crypto.com Card Top Up",
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

            // The shared top-up: the fiat export names both ends (Cash -> Card), the card export only
            // that £400 arrived (placeholder -> Card). Both records are kept...
            val topUpPlaceholderId = accounts.first { it.name == "Crypto.com Card Top Up" }.id
            val topUps =
                repositories.transactionRepository
                    .getTransactionsByAccount(cardId)
                    .first()
                    .filter { it.targetAccountId == cardId && it.amount.toDisplayValue().compareTo(BigDecimal("400.00")) == 0 }
            assertEquals(2, topUps.size, "both files' top-up records are kept")
            val fiatRecord = topUps.single { it.sourceAccountId == cashId }
            val cardRecord = topUps.single { it.sourceAccountId == topUpPlaceholderId }

            // ...but the placeholder one — the record that cannot say where the money came from — is the
            // excluded one, linked to the fiat record, so the movement counts once and off the real wallet.
            assertTrue(
                cardRecord.attributes.any { it.attributeType.name == "excluded" && it.value == "reconciled" },
                "the placeholder record is excluded as reconciled",
            )
            assertTrue(
                fiatRecord.attributes.none { it.attributeType.name == "excluded" },
                "the record naming both ends stays counted",
            )
            val relationships = repositories.transferRelationshipRepository.getByTransfer(fiatRecord.id).first()
            val reconciledLink = relationships.single { it.relationshipType.name == "reconciled" }
            assertEquals(fiatRecord.id, reconciledLink.id1)
            assertEquals(cardRecord.id, reconciledLink.id2)
        }

    /** Re-imports an already-imported file (plan + execute) with its built-in strategy. */
    private suspend fun reimport(
        importId: CsvImportId,
        strategyName: String,
    ): CsvReimportResult {
        val current = repositories.csvImportRepository.getImport(importId).first()!!
        val strategy =
            repositories.csvImportStrategyRepository
                .getAllStrategies()
                .first()
                .first { it.name == strategyName }
        val currencies = repositories.currencyRepository.getAllCurrencies().first()
        val plan =
            planCsvReimport(
                csvImport = current,
                strategy = strategy,
                sourceAccountOverride = null,
                currencies = currencies,
                accountMappingRepository = repositories.accountMappingRepository,
                accountRepository = repositories.accountRepository,
                csvImportRepository = repositories.csvImportRepository,
                transactionRepository = repositories.transactionRepository,
                relationshipRepository = repositories.transferRelationshipRepository,
                transferSourceRepository = repositories.transferSourceRepository,
            )
        return executeCsvReimport(
            plan = plan,
            csvImport = current,
            strategy = strategy,
            sourceAccountOverride = null,
            currencies = currencies,
            accountMappingRepository = repositories.accountMappingRepository,
            accountRepository = repositories.accountRepository,
            csvImportRepository = repositories.csvImportRepository,
            maintenance = maintenance,
            importEngine = repositories.importEngine,
            cryptoRepository = repositories.cryptoRepository,
            tradeRepository = repositories.tradeRepository,
        )
    }

    @Test
    fun sameConversionWordedDifferentlyByTwoExports_isBookedOnce() =
        runTest {
            // crypto.com's crypto export calls a purchase "GBP -> TGBP" while its newer cash export calls
            // the same conversion "Bought TGBP". A trade's instant, accounts, assets and amounts pin one
            // conversion, so the wording must not make it two.
            repositories.currencyRepository.upsertCurrencyByCode("GBP", "British Pound")
            val first = stage("fiat_transactions_record_20231120_085814.csv", fiatRows)
            assertEquals(1, applyAll(listOf(first)).filesImported)

            val reworded =
                listOf(row("2023-11-20 09:00:00", "Bought TGBP", "GBP", "5000.0", "TGBP", "5000.0", "5000.0", "viban_purchase"))
            val second = stage("cash_transactions_record_20260707_184457.csv", reworded)
            applyAll(listOf(second))
            repositories.maintenanceService.refreshMaterializedViews()

            val accounts = repositories.accountRepository.getAllAccounts().first()
            val cashId = accounts.first { it.name == "Crypto.com Cash" }.id
            val buys =
                repositories.tradeRepository
                    .getTradesByAccount(cashId)
                    .first()
                    .filter { it.fromAccountId == cashId && it.from.toDisplayValue().compareTo(BigDecimal("5000.00")) == 0 }
            assertEquals(1, buys.size, "the conversion is booked once, whichever export words it how")
        }

    @Test
    fun bankLegImportedLater_isReconciledRetroactivelyOnReimport() =
        runTest {
            // The order the real world produces: the wallet's own export is imported first, so its deposit
            // record is the only one and counts. The bank feed arrives later, and re-importing the wallet
            // export must then reconcile the now-redundant placeholder record instead of leaving the
            // deposit counted twice.
            repositories.currencyRepository.upsertCurrencyByCode("GBP", "British Pound")
            val fiat = stage("fiat_transactions_record_20231120_085814.csv", fiatRows)
            assertEquals(1, applyAll(listOf(fiat)).filesImported)

            val accounts = repositories.accountRepository.getAllAccounts().first()
            val cashId = accounts.first { it.name == "Crypto.com Cash" }.id
            val gbp = repositories.currencyRepository.getCurrencyByCode("GBP").first()!!
            val bankId =
                repositories.importEngine.createAccount(Account(id = AccountId(0), name = "Monzo", openingDate = now), Source.Manual)
            createTransfer(
                Transfer(
                    id = TransferId(0),
                    timestamp = Instant.parse("2023-11-17T02:18:14Z"),
                    description = "BI6055443",
                    sourceAccountId = bankId,
                    targetAccountId = cashId,
                    amount = Money.fromDisplayValue(BigDecimal("2000.00"), gbp),
                ),
            )

            val result = reimport(fiat.id, "Crypto.com Fiat")
            assertEquals(1, result.counterpartyReconciledRows.size, "the deposit row is re-run")
            repositories.maintenanceService.refreshMaterializedViews()

            val deposits =
                repositories.transactionRepository
                    .getTransactionsByAccount(cashId)
                    .first()
                    .filter { it.targetAccountId == cashId && it.amount.toDisplayValue().compareTo(BigDecimal("2000.00")) == 0 }
            assertEquals(2, deposits.size, "both records are kept")
            assertTrue(
                deposits.single { it.sourceAccountId != bankId }.attributes.any {
                    it.attributeType.name == "excluded" && it.value == "reconciled"
                },
                "the wallet's placeholder record is now excluded",
            )
            val cashBalance =
                repositories.transactionRepository
                    .getAccountBalances()
                    .first()
                    .single { it.accountId == cashId && it.balance.asset.code == "GBP" }
            assertEquals("-3446.03", cashBalance.balance.toDisplayValue().toString(), "the deposit counts once")
        }

    @Test
    fun bankLegAlreadyImported_reconcilesTheWalletsOwnDepositRecord() =
        runTest {
            // The bank knows both ends of an FPS deposit (its feed resolves the wallet by sort code +
            // account number), while crypto.com's own export says only "GBP Deposit (via FPS)" and books a
            // placeholder counterparty. Nothing links the two records but the wallet account, the
            // direction and the amount — so without the unidentified-counterparty reconcile the deposit
            // lands in the wallet twice.
            repositories.currencyRepository.upsertCurrencyByCode("GBP", "British Pound")
            val gbp = repositories.currencyRepository.getCurrencyByCode("GBP").first()!!
            val bankId =
                repositories.importEngine.createAccount(Account(id = AccountId(0), name = "Monzo", openingDate = now), Source.Manual)
            val cashId =
                repositories.importEngine.createAccount(
                    Account(id = AccountId(0), name = "Crypto.com Cash", openingDate = now),
                    Source.Manual,
                )
            // The bank's own record, stamped nine hours before crypto.com's (statement exports and bank
            // feeds rarely agree to the minute).
            createTransfer(
                Transfer(
                    id = TransferId(0),
                    timestamp = Instant.parse("2023-11-17T02:18:14Z"),
                    description = "BI6055443",
                    sourceAccountId = bankId,
                    targetAccountId = cashId,
                    amount = Money.fromDisplayValue(BigDecimal("2000.00"), gbp),
                ),
            )

            val fiat = stage("fiat_transactions_record_20231120_085814.csv", fiatRows)
            assertEquals(1, applyAll(listOf(fiat)).filesImported)
            repositories.maintenanceService.refreshMaterializedViews()

            val deposits =
                repositories.transactionRepository
                    .getTransactionsByAccount(cashId)
                    .first()
                    .filter { it.targetAccountId == cashId && it.amount.toDisplayValue().compareTo(BigDecimal("2000.00")) == 0 }
            assertEquals(2, deposits.size, "both records are kept")
            val walletRecord = deposits.single { it.sourceAccountId != bankId }
            val bankRecord = deposits.single { it.sourceAccountId == bankId }
            assertTrue(
                walletRecord.attributes.any { it.attributeType.name == "excluded" && it.value == "reconciled" },
                "the wallet's placeholder record is excluded, so the deposit is counted once",
            )
            assertTrue(bankRecord.attributes.none { it.attributeType.name == "excluded" }, "the bank's record stays counted")
            val reconciledLink =
                repositories.transferRelationshipRepository
                    .getByTransfer(walletRecord.id)
                    .first()
                    .single { it.relationshipType.name == "reconciled" }
            assertEquals(walletRecord.id, reconciledLink.id1)
            assertEquals(bankRecord.id, reconciledLink.id2)

            // The wallet balance reflects one deposit: +2000 in, -400 to the card, -5055.89 withdrawn,
            // -5000 converted to TGBP and +5009.86 converted back.
            val cashBalance =
                repositories.transactionRepository
                    .getAccountBalances()
                    .first()
                    .single { it.accountId == cashId && it.balance.asset.code == "GBP" }
            assertEquals("-3446.03", cashBalance.balance.toDisplayValue().toString())
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
