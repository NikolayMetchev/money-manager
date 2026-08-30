@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.csv

import com.moneymanager.csvimporter.bulkApplyCsv
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * End-to-end cover for the built-in Binance CSV strategy: a staged export goes through the real
 * engine and must produce the right accounts, balances and trades.
 *
 * The row data is taken from the shapes a real Binance export contains — a multi-fill order whose
 * legs all carry one timestamp, a dust sweep whose credits cannot be attributed to its debits, and
 * the reward operations no Binance API endpoint exposes.
 */
class BinanceCsvE2ETest : DbTest() {
    override val installBuiltInStrategies: Boolean = true

    private val now = Clock.System.now()

    private val headers = listOf("User_ID", "UTC_Time", "Account", "Operation", "Coin", "Change", "Remark")

    private val maintenance =
        object : Maintenance {
            override suspend fun reindex(): Duration = Duration.ZERO

            override suspend fun vacuum(): Duration = Duration.ZERO

            override suspend fun analyze(): Duration = Duration.ZERO

            override suspend fun refreshMaterializedViews(): Duration = Duration.ZERO

            override suspend fun fullRefreshMaterializedViews(): Duration = Duration.ZERO
        }

    private fun row(
        time: String,
        operation: String,
        coin: String,
        change: String,
        remark: String = "",
    ): List<String> = listOf("53064551", time, "Spot", operation, coin, change, remark)

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
            onProgress = { },
            cryptoRepository = repositories.cryptoRepository,
        )

    private suspend fun accountByName(name: String) =
        repositories.accountRepository
            .getAllAccounts()
            .first()
            .firstOrNull { it.name == name }

    private suspend fun balanceOf(
        accountName: String,
        assetCode: String,
    ): String? {
        repositories.maintenanceService.refreshMaterializedViews()
        val account = assertNotNull(accountByName(accountName), "account '$accountName' exists")
        return repositories.transactionRepository
            .getAccountBalances()
            .first()
            .firstOrNull { it.accountId == account.id && it.balance.asset.code == assetCode }
            ?.balance
            ?.toDisplayValue()
            ?.toString()
    }

    @Test
    fun rewardOperations_landInTheirOwnAccountsAndCreateCryptoOnDemand() =
        runTest {
            val file =
                stage(
                    "0452506e-8714-11ee-9934-06655da838d5-1.csv",
                    listOf(
                        row("2023-01-02 03:04:05", "Staking Rewards", "DOT", "1.5"),
                        row("2023-01-03 03:04:05", "BNB Vault Rewards", "BNB", "0.002"),
                        row("2023-01-04 03:04:05", "Launchpool Earnings Withdrawal", "BNB", "0.5", "Binance Launchpool"),
                        row("2023-01-05 03:04:05", "Commission History", "BNB", "0.01"),
                        row("2023-01-06 03:04:05", "Simple Earn Flexible Subscription", "BNB", "-2.0", "Binance Earn"),
                        row("2023-01-07 03:04:05", "Simple Earn Flexible Interest", "BNB", "0.03", "Binance Earn"),
                    ),
                )
            val result = applyAll(listOf(file))
            assertEquals(1, result.filesImported)
            assertEquals(0, result.filesSkippedNoStrategy)

            // DOT is created on demand, exactly as the API importer would.
            assertNotNull(repositories.cryptoRepository.getCryptoAssetByCode("DOT").first())

            // Each product's income has its own account rather than being pooled into Earn.
            assertEquals("-1.5", balanceOf("Binance Staking Rewards", "DOT"))
            assertEquals("-0.002", balanceOf("Binance Vault Rewards", "BNB"))
            assertEquals("-0.5", balanceOf("Binance Launchpool Rewards", "BNB"))
            assertEquals("-0.01", balanceOf("Binance Commission", "BNB"))
            // Earn holds the subscribed principal, its rewards account the interest.
            assertEquals("2", balanceOf("Binance Earn", "BNB"))
            assertEquals("-0.03", balanceOf("Binance Earn Rewards", "BNB"))

            // Binance nets: -2 subscribed +0.002 +0.5 +0.01 +0.03 = -1.458 BNB
            assertEquals("-1.458", balanceOf("Binance", "BNB"))
            assertEquals("1.5", balanceOf("Binance", "DOT"))
        }

    @Test
    fun depositAndWithdrawal_splitFiatFromCryptoFundingLikeTheApiDoes() =
        runTest {
            val file =
                stage(
                    "deposits.csv",
                    listOf(
                        row("2023-01-02 03:04:05", "Deposit", "GBP", "500.00"),
                        row("2023-01-03 03:04:05", "Deposit", "BTC", "0.5"),
                        row("2023-01-04 03:04:05", "Withdraw", "BTC", "-0.1", "Withdraw fee is included"),
                    ),
                )
            applyAll(listOf(file))

            assertEquals("-500", balanceOf("Binance Bank", "GBP"), "fiat funding books against the bank account")
            assertEquals("-0.4", balanceOf("Binance Funding", "BTC"), "crypto funding books against the funding account")
            assertEquals("500", balanceOf("Binance", "GBP"))
            assertEquals("0.4", balanceOf("Binance", "BTC"))
        }

    @Test
    fun aMultiFillOrder_becomesOneTradeAndOneFeeTransfer() =
        runTest {
            // The real 2022-11-14 20:32:54 shape, trimmed: several fills per leg all stamped with the
            // same second, plus a fee in a third asset.
            val file =
                stage(
                    "trades.csv",
                    listOf(
                        row("2022-11-14 20:32:54", "Transaction Sold", "BTC", "-0.04382"),
                        row("2022-11-14 20:32:54", "Transaction Revenue", "GBP", "605.57925400"),
                        row("2022-11-14 20:32:54", "Transaction Sold", "BTC", "-0.90716"),
                        row("2022-11-14 20:32:54", "Transaction Revenue", "GBP", "12536.54297800"),
                        row("2022-11-14 20:32:54", "Transaction Fee", "BNB", "-0.00090052"),
                    ),
                )
            applyAll(listOf(file))

            val binance = assertNotNull(accountByName("Binance"))
            val trades = repositories.tradeRepository.getTradesByAccount(binance.id).first()
            assertEquals(1, trades.size, "the four legs fold into a single trade, not four")
            val trade = trades.single()
            assertEquals("BTC", trade.from.asset.code)
            assertEquals("0.95098", trade.from.toDisplayValue().toString(), "the BTC fills sum")
            assertEquals("GBP", trade.to.asset.code)
            assertEquals("13142.122232", trade.to.toDisplayValue().toString(), "the GBP fills sum")

            // The fee is its own transfer, because a trade row has no fee field. It goes to the same
            // account the Binance API strategy books fees to.
            assertEquals("0.00090052", balanceOf("Binance Fees", "BNB"))

            // The suspense account is created while the legs are being mapped (assembly happens after),
            // but every leg became the trade, so it holds nothing. A non-zero balance here would mean a
            // group failed to resolve.
            assertNull(balanceOf("Binance Trading", "BTC"), "no leg fell through to the suspense account")
            assertNull(balanceOf("Binance Trading", "GBP"), "no leg fell through to the suspense account")
        }

    @Test
    fun aDustSweep_becomesLinkedConversionLegsRatherThanFabricatedTrades() =
        runTest {
            // A real sweep: three assets swept, three BNB credits, and nothing in the file saying which
            // credit came from which debit. Assembling trades here would have to invent the pairing.
            val file =
                stage(
                    "dust.csv",
                    listOf(
                        row("2021-01-01 09:43:33", "Small Assets Exchange BNB (Spot)", "REEF", "-90.89657258"),
                        row("2021-01-01 09:43:33", "Small Assets Exchange BNB (Spot)", "BNB", "0.00062058"),
                        row("2021-01-01 09:43:33", "Small Assets Exchange BNB (Spot)", "BNB", "0.03251993"),
                        row("2021-01-01 09:43:33", "Small Assets Exchange BNB (Spot)", "BNB", "0.00050172"),
                        row("2021-01-01 09:43:33", "Small Assets Exchange BNB (Spot)", "PSG", "-0.00187671"),
                        row("2021-01-01 09:43:33", "Small Assets Exchange BNB (Spot)", "ASR", "-0.00294372"),
                    ),
                )
            applyAll(listOf(file))

            val binance = assertNotNull(accountByName("Binance"))
            assertEquals(
                0,
                repositories.tradeRepository
                    .getTradesByAccount(binance.id)
                    .first()
                    .size,
                "a dust sweep never assembles into trades",
            )

            // Every swept asset leaves the Binance account in full and the BNB arrives in full, so the
            // per-asset balances stay exact; the conversion account holds the mixed-asset residual.
            assertEquals("-90.89657258", balanceOf("Binance", "REEF"))
            assertEquals("0.03364223", balanceOf("Binance", "BNB"))
            assertEquals("90.89657258", balanceOf("Binance Conversions", "REEF"))
            assertEquals("-0.03364223", balanceOf("Binance Conversions", "BNB"))
        }

    @Test
    fun reimportingTheSameFileCreatesNothingNew() =
        runTest {
            val rows =
                listOf(
                    row("2022-11-14 20:32:54", "Transaction Sold", "BTC", "-0.04382"),
                    row("2022-11-14 20:32:54", "Transaction Revenue", "GBP", "605.57925400"),
                    row("2022-11-14 20:32:54", "Transaction Fee", "BNB", "-0.00090052"),
                    row("2023-01-02 03:04:05", "Staking Rewards", "DOT", "1.5"),
                )
            val file = stage("idempotent.csv", rows)
            applyAll(listOf(file))

            val binance = assertNotNull(accountByName("Binance"))
            val tradesAfterFirst = repositories.tradeRepository.getTradesByAccount(binance.id).first().size
            val btcAfterFirst = balanceOf("Binance", "BTC")

            applyAll(repositories.csvImportRepository.getAllImports().first())

            assertEquals(
                tradesAfterFirst,
                repositories.tradeRepository
                    .getTradesByAccount(binance.id)
                    .first()
                    .size,
                "a second pass over the same file books no second trade",
            )
            assertEquals(btcAfterFirst, balanceOf("Binance", "BTC"), "and no second transfer")
        }

    @Test
    fun aLegacySixColumnExportIsSkippedRatherThanImported() =
        runTest {
            // Legacy exports use an older Operation vocabulary ("Savings purchase" for what the modern
            // file calls "Simple Earn Flexible Subscription"), so importing both would book each event
            // twice under two descriptions.
            val legacyHeaders = headers.drop(1)
            val id =
                repositories.csvImportRepository.createImport(
                    fileName = "20210424.csv",
                    headers = legacyHeaders,
                    rows = listOf(listOf("2020-10-11 09:31:32", "Spot", "Savings purchase", "BNB", "-4.82096423", "")),
                    fileChecksum = "checksum-legacy",
                    fileLastModified = now,
                )
            val legacy = repositories.csvImportRepository.getImport(id).first()!!

            val result = applyAll(listOf(legacy))
            assertEquals(0, result.filesImported)
            assertEquals(1, result.filesSkippedNoStrategy, "no strategy claims a legacy export")
            assertTrue(repositories.accountRepository.getAllAccounts().first().none { it.name == "Binance" })
        }
}
