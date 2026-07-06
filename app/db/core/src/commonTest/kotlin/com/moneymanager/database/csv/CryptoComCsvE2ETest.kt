@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.csv

import com.moneymanager.csvimporter.bulkApplyCsv
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
            row("2023-11-19 09:00:00", "GBP -> TGBP", "GBP", "10.0", "TGBP", "10.0", "10.0", "viban_purchase"),
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
        )

    @Test
    fun importAll_routesFilesByName_mapsKinds_andReconcilesTheSharedTopUp() =
        runTest {
            val card = stage("card_transactions_record_20231120_210200.csv", cardRows)
            val fiat = stage("fiat_transactions_record_20231120_085814.csv", fiatRows)
            val crypto = stage("crypto_transactions_record_20231119_121744.csv", cryptoRows)

            val result = applyAll(listOf(card, fiat, crypto))

            // The card and fiat files matched their strategies by filename; the crypto file matched
            // neither built-in and was skipped instead of being misrouted.
            assertEquals(2, result.filesImported, "card + fiat should import")
            assertEquals(1, result.filesSkippedNoStrategy, "crypto_* file should be skipped")
            assertEquals(0, result.filesFailed)

            val accounts = repositories.accountRepository.getAllAccounts().first()
            val accountNames = accounts.map { it.name }.toSet()
            assertEquals(
                setOf(
                    "Crypto.com Card",
                    "Crypto.com Cash",
                    "Crypto.com TGBP",
                    "GBP Deposit (via FPS)",
                    "GBP Withdrawal (via FPS)",
                    "Spotify P3 C6 Ef4945",
                ),
                accountNames,
            )

            val cashId = accounts.first { it.name == "Crypto.com Cash" }.id
            val cardId = accounts.first { it.name == "Crypto.com Card" }.id
            val walletId = accounts.first { it.name == "Crypto.com TGBP" }.id
            val depositId = accounts.first { it.name == "GBP Deposit (via FPS)" }.id
            val withdrawalId = accounts.first { it.name == "GBP Withdrawal (via FPS)" }.id

            // Amounts in GBP minor units (scale factor 100).
            fun Money.pence(): Long = amount

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
            assertTrue(
                cashTransfers.any {
                    it.sourceAccountId == cashId && it.targetAccountId == walletId && it.amount.pence() == 500_000L
                },
                "viban_purchase: Cash -> wallet despite the positive amount",
            )
            assertTrue(
                cashTransfers.any {
                    it.sourceAccountId == walletId && it.targetAccountId == cashId && it.amount.pence() == 500_986L
                },
                "crypto_viban: wallet -> Cash",
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
}
