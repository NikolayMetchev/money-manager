@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.api

import com.moneymanager.apiimporter.importApiSessionExchange
import com.moneymanager.bigdecimal.BigInteger
import com.moneymanager.csvimporter.bulkApplyCsv
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * Bybit's per-wallet statement exports and the Bybit API describe one account, so importing both — in
 * either order — must count every movement once. The rows are a real account's history (UID and
 * addresses replaced), which overlaps the API in exactly two movements, each needing its own rule:
 *
 * * a **USDT deposit** the API dates only approximately: Bybit returns `successAt = 0` for old deposits,
 *   so the API books it at the start of the 30-day window it was fetched in, 4.6 days before the export's
 *   precise time. The export's record wins, in either order.
 * * a **BTC withdrawal** the Funding ledger books gross while the API books it net plus `withdrawFee`.
 *
 * Everything else the exports hold — the 2022 trade, the 2023 converts, trading fees, and the moves
 * between the Spot, Funding, Unified and Earn wallets — is older than the API can reach.
 */
class BybitCsvApiE2ETest : DbTest() {
    override val installBuiltInStrategies: Boolean = true

    private val now = Clock.System.now()

    private val uid = "12345678"

    private val maintenance =
        object : Maintenance {
            override suspend fun reindex(): Duration = Duration.ZERO

            override suspend fun vacuum(): Duration = Duration.ZERO

            override suspend fun analyze(): Duration = Duration.ZERO

            override suspend fun refreshMaterializedViews(): Duration = Duration.ZERO

            override suspend fun fullRefreshMaterializedViews(): Duration = Duration.ZERO
        }

    private suspend fun stageCsv(
        fileName: String,
        headers: List<String>,
        rows: List<List<String>>,
    ) = assertNotNull(
        repositories.csvImportRepository
            .getImport(
                repositories.csvImportRepository.createImport(
                    fileName = fileName,
                    headers = headers,
                    rows = rows,
                    fileChecksum = "checksum-$fileName",
                    fileLastModified = now,
                ),
            ).first(),
    )

    private suspend fun importCsv() {
        val spotHeaders = listOf("Uid", "Type", "Coin", "Amount", "Wallet Balance", "Time(UTC)")
        val spot2022 =
            stageCsv(
                "AssetChangeDetails_spot_${uid}_20220101_20221231_0.csv",
                spotHeaders,
                listOf(
                    listOf(uid, "internalAccountTransferWithdrawal", "KASTA", "-467.4316", "500", "2022-04-25 11:23:42"),
                    listOf(uid, "trade", "USDT", "-684.982144", "0.119229", "2022-01-05 13:18:18"),
                    listOf(uid, "trade", "KASTA", "492.8", "967.9244", "2022-01-05 13:18:18"),
                    listOf(uid, "trade", "USDT", "-15.424782", "685.101373", "2022-01-05 13:18:18"),
                    listOf(uid, "trade", "KASTA", "464.5", "464.5", "2022-01-05 13:18:18"),
                    listOf(uid, "trade", "KASTA", "11.1", "475.1355", "2022-01-05 13:18:18"),
                    listOf(uid, "trade", "USDT", "-645.473845", "700.526155", "2022-01-05 13:18:18"),
                    listOf(uid, "tradingFee", "KASTA", "-0.4928", "967.4316", "2022-01-05 13:18:18"),
                    listOf(uid, "tradingFee", "KASTA", "-0.0111", "475.1244", "2022-01-05 13:18:18"),
                    listOf(uid, "tradingFee", "KASTA", "-0.4645", "464.0355", "2022-01-05 13:18:18"),
                    listOf(uid, "userDeposit", "USDT", "1346", "1346", "2022-01-02 14:36:12"),
                ),
            )
        val spot2023 =
            stageCsv(
                "AssetChangeDetails_spot_${uid}_20230101_20231231_0.csv",
                spotHeaders,
                listOf(
                    listOf(uid, "internalAccountTransferWithdrawal", "USDT", "-0.119229", "0", "2023-11-13 09:04:07"),
                    listOf(uid, "internalAccountTransferWithdrawal", "BTC", "-0.00039664", "0", "2023-11-13 09:04:06"),
                    // Converts leave Type blank, and this one's legs straddle a second boundary.
                    listOf(uid, "", "BTC", "0.000205", "0.00039664", "2023-11-11 00:16:59"),
                    listOf(uid, "", "KASTA", "-500", "0", "2023-11-11 00:16:58"),
                    listOf(uid, "", "KASTA", "-467.4316", "500", "2023-11-11 00:11:51"),
                    listOf(uid, "", "BTC", "0.00019164", "0.00019164", "2023-11-11 00:11:51"),
                    listOf(uid, "internalAccountTransferDeposit", "KASTA", "467.4316", "967.4316", "2023-11-11 00:10:00"),
                ),
            )
        val fund2023 =
            stageCsv(
                "AssetChangeDetails_fund_${uid}_20230101_20231231_0.csv",
                listOf("Uid", "Date & Time(UTC)", "Coin", "QTY", "Type", "Account Balance", "Description"),
                listOf(
                    listOf(uid, "2023-11-13 09:18:18", "BTC", "-0.00039664", "Withdraw", "0", "Withdrawal"),
                    listOf(
                        uid,
                        "2023-11-13 09:05:55",
                        "BTC",
                        "0.00039664",
                        "Transfer in",
                        "0.00039664",
                        "Transfer from Unified Trading Account",
                    ),
                    listOf(uid, "2023-11-11 00:10:00", "KASTA", "-467.4316", "Transfer out", "0", "Transfer to Spot Account"),
                    listOf(uid, "2023-03-24 09:35:02", "KASTA", "467.4316", "Transfer in", "467.4316", "Transfer from Earn Account"),
                ),
            )
        val uta2023 =
            stageCsv(
                "AssetChangeDetails_uta_${uid}_20230101_20231231_0.csv",
                listOf(
                    "Uid",
                    "Currency",
                    "Contract",
                    "Type",
                    "Direction",
                    "Quantity",
                    "Position",
                    "Filled Price",
                    "Funding",
                    "Fee Paid",
                    "Cash Flow",
                    "Change",
                    "Wallet Balance",
                    "Action",
                    "Time(UTC)",
                ),
                listOf(
                    utaRow("BTC", "TRANSFER_OUT", "-0.00039664", "2023-11-13 09:05:54"),
                    utaRow("USDT", "TRANSFER_IN", "0.119229", "2023-11-13 09:04:07"),
                    utaRow("BTC", "TRANSFER_IN", "0.00039664", "2023-11-13 09:04:06"),
                ),
            )
        val result =
            bulkApplyCsv(
                imports = listOf(spot2022, spot2023, fund2023, uta2023),
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
        assertEquals(4, result.filesImported, "each ledger resolves to its Bybit CSV strategy")
    }

    private fun utaRow(
        currency: String,
        type: String,
        change: String,
        time: String,
    ) = listOf(uid, currency, "", type, "--", "0", "0", "0", "0", "0", change, change, "0", "--", time)

    private suspend fun importApi() {
        val strategy = assertNotNull(repositories.apiImportStrategyRepository.getStrategyByName("Bybit").first())
        val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-os", "test-machine"))
        val sessionId = repositories.apiSessionRepository.createSession(deviceId, now, null)

        suspend fun stage(
            marker: String,
            json: String,
        ) {
            val requestId =
                repositories.apiSessionRepository.insertRequest(sessionId, "GET", "https://api.bybit.com/$marker", emptyMap())
            repositories.apiSessionRepository.insertResponse(requestId, sessionId, json)
        }
        // The real responses, down to successAt = "0" and the fetch window in the marker URL.
        stage(
            "v5/asset/deposit/query-record?ep=v5/asset/deposit/query-record&ws=1640736000000&we=1643327999999&pg=0",
            """
            {"retCode":0,"retMsg":"success","result":{"rows":[{"coin":"USDT","chain":"ETH","amount":"1346",
            "txID":"0xdeposit","status":3,"toAddress":"0xbybit","tag":"","depositFee":"","successAt":"0",
            "confirmations":"30","txIndex":"200","blockHash":"","batchReleaseLimit":"-1","depositType":"0",
            "fromAddress":"","id":"3562579"}],"nextPageCursor":""},"retExtInfo":{},"time":1790930541991}
            """.trimIndent(),
        )
        stage(
            "v5/asset/withdraw/query-record?ep=v5/asset/withdraw/query-record&ws=1697760000000&we=1700351999999&pg=0",
            """
            {"retCode":0,"retMsg":"success","result":{"rows":[{"coin":"BTC","chain":"BTC","amount":"0.00031164",
            "txID":"withdrawtx","status":"success","toAddress":"bc1qwithdrawaddr","tag":"","withdrawFee":"0.000085",
            "createTime":"1699867096000","updateTime":"1699868403000","withdrawId":"26711021","withdrawType":0}],
            "nextPageCursor":""},"retExtInfo":{},"time":1790930647825}
            """.trimIndent(),
        )
        importApiSessionExchange(
            apiSessionRepository = repositories.apiSessionRepository,
            accountRepository = repositories.accountRepository,
            currencyRepository = repositories.currencyRepository,
            cryptoRepository = repositories.cryptoRepository,
            sessionId = sessionId,
            strategy = strategy,
            importEngine = repositories.importEngine,
        )
    }

    private suspend fun accountByName(name: String) =
        repositories.accountRepository
            .getAllAccounts()
            .first()
            .firstOrNull { it.name == name }

    /** Bybit's counted balance per asset, zero balances dropped. */
    private suspend fun bybitBalances(): Map<String, String> {
        repositories.maintenanceService.refreshMaterializedViews()
        val bybit = assertNotNull(accountByName("Bybit"))
        return repositories.transactionRepository
            .getAccountBalances()
            .first()
            .filter { it.accountId == bybit.id && it.balance.amount != BigInteger.ZERO }
            .associate { it.balance.asset.code to it.balance.toDisplayValue().toString() }
    }

    private suspend fun reconciledLinkCount(): Int {
        val bybit = assertNotNull(accountByName("Bybit"))
        val transfers = repositories.transactionRepository.getTransactionsByAccount(bybit.id).first()
        return repositories.transferRelationshipRepository
            .getByTransfers(transfers.map { it.id })
            .count { it.relationshipType.id.id == WellKnownIds.RECONCILED_RELATIONSHIP_TYPE_ID }
    }

    private suspend fun countedDepositTimestamps(): List<String> {
        val bybit = assertNotNull(accountByName("Bybit"))
        return repositories.transactionRepository
            .getTransactionsByAccount(bybit.id)
            .first()
            .filter { t ->
                t.amount.asset.code == "USDT" &&
                    t.targetAccountId == bybit.id &&
                    t.attributes.none { it.attributeType.id.id == WellKnownIds.EXCLUDED_ATTR_TYPE_ID }
            }.map { it.timestamp.toString() }
    }

    // Spot ends holding the 0.119229 USDT the trade left over, which then moved to the Unified wallet: still
    // Bybit. The KASTA bought, parked in Earn and converted to BTC nets to zero, and that BTC was withdrawn.
    private val exportBalances = mapOf("USDT" to "0.119229")

    @Test
    fun theExportsAloneBalanceTheAccount() =
        runTest {
            importCsv()

            assertEquals(exportBalances, bybitBalances())
            val bybit = assertNotNull(accountByName("Bybit"))
            assertEquals(
                3,
                repositories.tradeRepository
                    .getTradesByAccount(bybit.id)
                    .first()
                    .size,
                "the three-fill 2022 order folds into one trade, and each 2023 convert is one trade",
            )
        }

    @Test
    fun theApiAfterTheExportsRebooksNothing() =
        runTest {
            importCsv()
            importApi()

            assertEquals(exportBalances, bybitBalances())
            assertEquals(listOf("2022-01-02T14:36:12Z"), countedDepositTimestamps(), "the deposit keeps its real time")
            assertEquals(2, reconciledLinkCount(), "the deposit and the withdrawal are each linked once")
        }

    @Test
    fun theExportsAfterTheApiRebookNothing() =
        runTest {
            importApi()
            importCsv()

            assertEquals(exportBalances, bybitBalances())
            assertEquals(
                listOf("2022-01-02T14:36:12Z"),
                countedDepositTimestamps(),
                "the export's precise deposit supersedes the API's window-start placeholder",
            )
            assertEquals(2, reconciledLinkCount(), "the deposit and the withdrawal are each linked once")
        }
}
