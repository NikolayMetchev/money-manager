package com.moneymanager.database.csv

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.csvimporter.bulkApplyCsv
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Trade
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.reconciliation.OtherSideSuggestion
import com.moneymanager.reconciliation.acceptOtherSideSuggestions
import com.moneymanager.reconciliation.planAutoLinks
import com.moneymanager.reconciliation.realAccounts
import com.moneymanager.reconciliation.suggestOtherSides
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A Revolut exchange row that names no currency waits in Manual Entries as a placeholder; the Koinly trade at the same instant
 * is the only record of what the other side was. Taking that record books the trade and stores the match with it.
 */
class RevolutKoinlySuggestionE2ETest : DbTest() {
    override val installBuiltInStrategies: Boolean = true

    private val now = Clock.System.now()

    private val revolutHeaders =
        listOf("Type", "Product", "Started Date", "Completed Date", "Description", "Amount", "Fee", "Currency", "State", "Balance")

    private val koinlyHeaders =
        listOf(
            "ID (read-only)",
            "Date (UTC)",
            "Type",
            "Tag",
            "From Wallet (read-only)",
            "From Wallet ID",
            "From Amount",
            "From Currency",
            "To Wallet (read-only)",
            "To Wallet ID",
            "To Amount",
            "To Currency",
            "Fee Amount",
            "Fee Currency",
            "Net Worth Amount",
            "Net Worth Currency",
            "Fee Worth Amount",
            "Fee Worth Currency",
            "Net Value (read-only)",
            "Fee Value (read-only)",
            "Value Currency (read-only)",
            "Deleted",
            "From Source (read-only)",
            "To Source (read-only)",
            "Negative Balances (read-only)",
            "Missing Rates (read-only)",
            "Missing Cost Basis (read-only)",
            "Synced To Accounting At (UTC read-only)",
            "TxSrc",
            "TxDest",
            "TxHash",
            "Description",
        )

    private val maintenance =
        object : Maintenance {
            override suspend fun reindex(): Duration = Duration.ZERO

            override suspend fun vacuum(): Duration = Duration.ZERO

            override suspend fun analyze(): Duration = Duration.ZERO

            override suspend fun refreshMaterializedViews(): Duration = Duration.ZERO

            override suspend fun fullRefreshMaterializedViews(): Duration = Duration.ZERO
        }

    private val revolutRow =
        listOf(
            "Exchange",
            "Current",
            "2020-05-10 01:15:27",
            "2020-05-10 01:15:27",
            "Transfer to Revolut Ltd",
            "-1000.00",
            "0.00",
            "EUR",
            "COMPLETED",
            "0.00",
        )

    private val koinlyTrade =
        koinlyHeaders.map {
            mapOf(
                "ID (read-only)" to "K1",
                "Date (UTC)" to "2020-05-10 01:15:27",
                "Type" to "trade",
                "From Wallet (read-only)" to "Revolut;revolut",
                "From Amount" to "1000.0",
                "From Currency" to "EUR;12",
                "To Wallet (read-only)" to "Revolut;revolut",
                "To Amount" to "0.1330735",
                "To Currency" to "BTC;1",
                "Deleted" to "false",
            )[it].orEmpty()
        }

    private suspend fun stage(
        fileName: String,
        headers: List<String>,
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

    private suspend fun apply(import: CsvImport) =
        bulkApplyCsv(
            imports = listOf(import),
            sourceAccountOverride = null,
            strategies = repositories.csvImportStrategyRepository.getAllStrategies().first(),
            currencies = repositories.currencyRepository.getAllCurrencies().first(),
            accountMappingRepository = repositories.accountMappingRepository,
            accountRepository = repositories.accountRepository,
            csvImportRepository = repositories.csvImportRepository,
            maintenance = maintenance,
            importEngine = repositories.importEngine,
            onProgress = { },
            cryptoRepository = repositories.cryptoRepository,
            tradeRepository = repositories.tradeRepository,
        )

    private suspend fun suggestions(): List<OtherSideSuggestion> {
        val placeholders =
            repositories.tradeRepository.getPendingManualTrades(WellKnownIds.MANUAL_ENTRY_ATTR_TYPE_NAME, "UNKNOWN").first()
        val shadowIds =
            repositories.reconciliationLinkRepository
                .getShadowAccounts()
                .first()
                .map { it.accountId }
        val shadowTrades: List<Trade> =
            placeholders.flatMap {
                repositories.tradeRepository.getTradesByAccountsAndDateRange(shadowIds, it.timestamp - 1.seconds, it.timestamp + 1.seconds)
            }
        return suggestOtherSides(placeholders.map { it to "UNKNOWN" }, shadowTrades).values.toList()
    }

    @Test
    fun `taking the Koinly record of an unnamed exchange books the trade and stores the match`() =
        runDbTest {
            apply(stage("account-statement_eur.csv", revolutHeaders, listOf(revolutRow)))
            apply(stage("koinly.csv", koinlyHeaders, listOf(koinlyTrade)))

            val suggestion = suggestions().single()
            assertEquals("BTC", suggestion.otherSide.asset.code)
            assertEquals(0, suggestion.otherSide.toDisplayValue().compareTo(BigDecimal("0.1330735")))

            val shadow = repositories.reconciliationLinkRepository.getShadowAccounts().first()
            val accounts = repositories.accountRepository.getAllAccounts().first()
            val sources = repositories.reconciliationLinkRepository.getSources().first()
            val links =
                sources.flatMap { planAutoLinks(it, shadow, emptyList(), realAccounts(accounts, shadow)).entries }.associate {
                    it.key to
                        it.value
                }
            repositories.importEngine.acceptOtherSideSuggestions(listOf(suggestion), links)

            // The placeholder is gone from Manual Entries and a real EUR -> BTC trade stands on the Revolut account.
            assertEquals(emptyList(), suggestions())
            val revolut = accounts.single { it.name == "Revolut" }
            val real =
                repositories.tradeRepository
                    .getTradesByAccount(revolut.id)
                    .first()
                    .single { it.to.asset.code == "BTC" }
            assertEquals(0, real.from.toDisplayValue().compareTo(BigDecimal("1000")))

            // The Koinly wallet now mirrors the Revolut account, and the two trades are one stored match.
            assertTrue(
                repositories.reconciliationLinkRepository
                    .getLinks()
                    .first()
                    .any { it.realAccountId == revolut.id },
            )
            val legs = repositories.reconciliationLinkRepository.getLegs(listOf(revolut.id) + shadow.map { it.accountId })
            val matched = legs.filter { it.transactionId == real.id.id || it.transactionId == suggestion.shadowTrade.id.id }
            assertNotNull(matched.flatMap { it.matchIds }.firstOrNull())
            assertEquals(1, matched.flatMap { it.matchIds }.distinct().size)
        }
}
