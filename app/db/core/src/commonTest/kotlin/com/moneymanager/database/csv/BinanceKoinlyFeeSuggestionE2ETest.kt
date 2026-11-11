package com.moneymanager.database.csv

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.csvimporter.bulkApplyCsv
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.reconciliation.FEE_SUGGESTION_WINDOW
import com.moneymanager.reconciliation.acceptFeeSuggestions
import com.moneymanager.reconciliation.suggestFees
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A Binance withdrawal "with the fee included" waits in Manual Entries for its fee. Koinly books the same movement as a net
 * transfer plus the fee on its own fee account, so the fee is read off it and the two fee records are stored as one match.
 */
class BinanceKoinlyFeeSuggestionE2ETest : DbTest() {
    override val installBuiltInStrategies: Boolean = true

    private val now = Clock.System.now()

    private val binanceHeaders = listOf("User_ID", "UTC_Time", "Account", "Operation", "Coin", "Change", "Remark")

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

    private val binanceRows =
        listOf(
            listOf("53064551", "2021-05-20 10:00:00", "Spot", "Deposit", "BTC", "0.5", ""),
            listOf("53064551", "2021-05-26 19:49:05", "Spot", "Withdraw", "BTC", "-0.27986608", "Withdraw fee is included"),
        )

    /** Koinly stamps the on-chain time, half a minute before Binance: the net transfer, and the fee beside it. */
    private fun koinlyTransfer(
        net: String = "0.27931608",
        fee: String = "0.00055",
    ) = koinlyHeaders.map {
        mapOf(
            "ID (read-only)" to "K1",
            "Date (UTC)" to "2021-05-26 19:48:36",
            "Type" to "transfer",
            "From Wallet (read-only)" to "Binance;binance",
            "From Amount" to net,
            "From Currency" to "BTC;1",
            "To Wallet (read-only)" to "Nexo;nexo",
            "To Amount" to net,
            "To Currency" to "BTC;1",
            "Fee Amount" to fee,
            "Fee Currency" to "BTC;1",
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

    private suspend fun pendingFees() =
        repositories.transactionRepository.getPendingManualEntries("Withdrawal fee", WellKnownIds.FEE_RELATIONSHIP_TYPE_NAME).first()

    private suspend fun suggestions(): List<com.moneymanager.reconciliation.FeeSuggestion> {
        val pending = pendingFees()
        val shadow = repositories.reconciliationLinkRepository.getShadowAccounts().first()
        val shadowIds = shadow.map { it.accountId }.toSet()
        val feeIds = shadow.filter { it.feeAccountFor != null }.map { it.accountId }.toSet()
        val slack = FEE_SUGGESTION_WINDOW + 5.seconds
        val transfers =
            pending
                .flatMap {
                    repositories.transactionRepository
                        .getTransactionsByDateRange(
                            it.timestamp - slack,
                            it.timestamp + slack,
                        ).first()
                }.filter { it.sourceAccountId in shadowIds }
                .distinctBy { it.id }
        return suggestFees(pending, transfers, feeIds).values.toList()
    }

    @Test
    fun `the fee of a Binance withdrawal is read off the Koinly record and stored as a match`() =
        runDbTest {
            apply(stage("binance.csv", binanceHeaders, binanceRows))
            assertEquals(1, pendingFees().size)
            apply(stage("koinly.csv", koinlyHeaders, listOf(koinlyTransfer())))

            val suggestion = suggestions().single()
            assertEquals(0, suggestion.fee.toDisplayValue().compareTo(BigDecimal("0.00055")))

            repositories.importEngine.acceptFeeSuggestions(
                listOf(suggestion),
                description = "Withdrawal fee",
                relationshipType = WellKnownIds.FEE_RELATIONSHIP_TYPE_NAME,
                excludedFromBalances = false,
                links = emptyMap(),
            )

            assertEquals(emptyList(), pendingFees())
            val shadowIds =
                repositories.reconciliationLinkRepository
                    .getShadowAccounts()
                    .first()
                    .map { it.accountId }
            val binance =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .single { it.name == "Binance" }
            val legs = repositories.reconciliationLinkRepository.getLegs(listOf(binance.id) + shadowIds)
            val matchIds = legs.filter { it.transactionId == suggestion.feeTransfer.id.id }.flatMap { it.matchIds }
            assertNotNull(matchIds.firstOrNull(), "the source's fee transfer is part of a stored match")
            assertEquals(
                matchIds.toSet(),
                legs.filter { it.description.contains("Withdrawal fee") && it.matchIds.isNotEmpty() }.flatMap { it.matchIds }.toSet(),
            )
        }

    @Test
    fun `no fee is suggested when the source's net and fee do not add up to the withdrawal`() =
        runDbTest {
            apply(stage("binance.csv", binanceHeaders, binanceRows))
            apply(stage("koinly.csv", koinlyHeaders, listOf(koinlyTransfer(fee = "0.001"))))

            assertEquals(emptyList(), suggestions())
        }
}
