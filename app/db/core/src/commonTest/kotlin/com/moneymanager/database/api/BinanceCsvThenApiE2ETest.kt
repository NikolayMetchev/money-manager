@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.api

import com.moneymanager.apiimporter.importApiSessionExchange
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
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * The Binance statement export and the Binance API describe the same account, so importing both must
 * leave every movement counted once — in **either** order. `BinanceCsvE2ETest` covers API-first;
 * this covers CSV-first, which is the order that actually broke on the default database: the exchange
 * import path derived cross-source reconciliation from `internalTransferReconcile`, a bridge config
 * Binance does not declare, so it ran with reconciliation off and re-booked 912 movements the export
 * had already recorded.
 *
 * Each staged movement below is the same real event described twice, and exercises a different rule:
 *
 * * a **crypto deposit** — the export cannot name the on-chain address, so it leaves a placeholder leg
 *   the API's identified leg must supersede (`unidentifiedCounterparty*`);
 * * a **Simple Earn subscription** — both sources name the same pair of accounts, so the plain
 *   cross-source reconcile catches it (`reconcileWindow`);
 * * a **multi-fill order** — the export folds the fills into one trade where the API reports each one,
 *   so the trade reconciler has to match a *set of incoming* fills against one existing trade
 *   (`tradeDedupePolicy`), and its two per-fill fee transfers against the export's fee rows;
 * * a **fiat and a crypto withdrawal** — the API reports the amount that left NET of the charge and
 *   books the charge separately, where the export has one gross row, so the amounts never match and
 *   only the gross-vs-net rule (`reconcileGrossAmount`) can pair them.
 */
class BinanceCsvThenApiE2ETest : DbTest() {
    override val installBuiltInStrategies: Boolean = true

    private val now = Clock.System.now()

    // 1_700_000_000_000 is 2023-11-14T22:13:20Z. The API stamps every movement to the millisecond while
    // the export truncates to the second, so the two never agree exactly - which is the whole point: an
    // exact-tuple match would classify these as plain duplicates and never reach the reconcile rules.
    private val depositMillis = 1_700_000_001_450L // 22:13:21.450, export says 22:13:21
    private val tradeMillis = 1_700_000_005_775L // 22:13:25.775, export says 22:13:25
    private val earnMillis = 1_700_000_006_320L // 22:13:26.320, export says 22:13:26

    // The two sources stamp a withdrawal minutes apart (the API when it was accepted, the export when
    // it settled) - past the plain reconcile window on purpose, which is why the gross-vs-net rule uses
    // the wider placeholder window instead.
    private val fiatWithdrawalMillis = 1_700_000_012_000L // 22:13:32, export says 22:20:00

    private val depositsJson =
        """
        [
          {"id":"d1","coin":"BTC","amount":"0.01000000","status":1,"address":"bc1qdepositaddr",
           "network":"BTC","txId":"onchain-dep-1","insertTime":$depositMillis}
        ]
        """.trimIndent()

    // One order filled twice. The API reports each fill with its own commission; the export reports the
    // order as a single Spend/Buy pair plus one fee row per fill.
    private val myTradesJson =
        """
        [{"symbol":"BTCUSDT","id":28457,"orderId":1000,"price":"40000.00","qty":"0.10000000",
          "quoteQty":"4000.00","commission":"4.00000000","commissionAsset":"USDT","time":$tradeMillis,
          "isBuyer":true},
         {"symbol":"BTCUSDT","id":28458,"orderId":1000,"price":"40000.00","qty":"0.20000000",
          "quoteQty":"8000.00","commission":"8.00000000","commissionAsset":"USDT","time":$tradeMillis,
          "isBuyer":true}]
        """.trimIndent()

    // amount is NET; totalFee is the charge on top. The export's single row is the sum, 1134.83.
    private val fiatWithdrawalsJson =
        """
        {"code":"000000","message":"success","data":[
          {"orderNo":"fw1","fiatCurrency":"GBP","amount":"1133.33","totalFee":"1.5","status":"Successful",
           "createTime":$fiatWithdrawalMillis}
        ],"total":1,"success":true}
        """.trimIndent()

    // Same shape for crypto, but the API also names the address it went to, where the export can only
    // say "Withdraw" - so this one has to pair on the gross total across *different* counterparties.
    private val withdrawalsJson =
        """
        [
          {"id":"w1","coin":"LINK","amount":"81.10800000","transactionFee":"1.25000000","status":6,
           "address":"0xwithdrawaddr","network":"ETH","txId":"onchain-wd-1","applyTime":"2023-11-14 22:19:30"}
        ]
        """.trimIndent()

    private val earnSubscriptionsJson =
        """
        {"rows":[
          {"purchaseId":26055,"asset":"XMR","amount":"5.81000000","time":$earnMillis,"status":"SUCCESS"}
        ],"total":1}
        """.trimIndent()

    private val csvHeaders = listOf("User_ID", "UTC_Time", "Account", "Operation", "Coin", "Change", "Remark")

    private fun row(
        time: String,
        operation: String,
        coin: String,
        change: String,
        remark: String = "",
    ): List<String> = listOf("53064551", time, "Spot", operation, coin, change, remark)

    private val maintenance =
        object : Maintenance {
            override suspend fun reindex(): Duration = Duration.ZERO

            override suspend fun vacuum(): Duration = Duration.ZERO

            override suspend fun analyze(): Duration = Duration.ZERO

            override suspend fun refreshMaterializedViews(): Duration = Duration.ZERO

            override suspend fun fullRefreshMaterializedViews(): Duration = Duration.ZERO
        }

    /** Stages and applies the export half: the same three movements the API responses describe. */
    private suspend fun importCsv() {
        val importId =
            repositories.csvImportRepository.createImport(
                fileName = "0452506e-8714-11ee-9934-06655da838d5-1.csv",
                headers = csvHeaders,
                rows =
                    listOf(
                        row("2023-11-14 22:13:21", "Deposit", "BTC", "0.01"),
                        // The order: one aggregate Spend/Buy pair, then a fee row per fill.
                        row("2023-11-14 22:13:25", "Transaction Spend", "USDT", "-12000"),
                        row("2023-11-14 22:13:25", "Transaction Buy", "BTC", "0.3"),
                        row("2023-11-14 22:13:25", "Transaction Fee", "USDT", "-4"),
                        row("2023-11-14 22:13:25", "Transaction Fee", "USDT", "-8"),
                        row("2023-11-14 22:13:26", "Simple Earn Flexible Subscription", "XMR", "-5.81", "Binance Earn"),
                        // Both withdrawals are GROSS - the fee is inside the amount, as the export's own
                        // remark says.
                        row("2023-11-14 22:19:00", "Withdraw", "LINK", "-82.358", "Withdraw fee is included"),
                        row("2023-11-14 22:20:00", "Fiat Withdrawal", "GBP", "-1134.83"),
                    ),
                fileChecksum = "checksum-binance-statement",
                fileLastModified = now,
            )
        val csvImport = assertNotNull(repositories.csvImportRepository.getImport(importId).first())
        val result =
            bulkApplyCsv(
                imports = listOf(csvImport),
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
        assertEquals(1, result.filesImported, "the Binance CSV strategy should claim the export")
    }

    /** Stages and imports the API half: the same three movements as the exchange reports them. */
    private suspend fun importApi() {
        val strategy = assertNotNull(repositories.apiImportStrategyRepository.getStrategyByName("Binance").first())
        val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-os", "test-machine"))
        val sessionId = repositories.apiSessionRepository.createSession("apikey", deviceId, now, null)

        suspend fun stage(
            marker: String,
            json: String,
        ) {
            val requestId =
                repositories.apiSessionRepository.insertRequest(
                    sessionId,
                    "GET",
                    "https://api.binance.com/$marker",
                    emptyMap(),
                )
            repositories.apiSessionRepository.insertResponse(requestId, sessionId, json)
        }
        stage("sapi/v1/capital/deposit/hisrec?ep=sapi/v1/capital/deposit/hisrec", depositsJson)
        stage("api/v3/myTrades?ep=api/v3/myTrades&fv=BTCUSDT", myTradesJson)
        stage("sapi/v1/fiat/orders?ep=sapi/v1/fiat/orders?transactionType=1", fiatWithdrawalsJson)
        stage("sapi/v1/capital/withdraw/history?ep=sapi/v1/capital/withdraw/history", withdrawalsJson)
        stage(
            "sapi/v1/simple-earn/flexible/history/subscriptionRecord" +
                "?ep=sapi/v1/simple-earn/flexible/history/subscriptionRecord",
            earnSubscriptionsJson,
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

    private suspend fun binanceBalances(): Map<String, String> {
        repositories.maintenanceService.refreshMaterializedViews()
        val binance = assertNotNull(accountByName("Binance"))
        return repositories.transactionRepository
            .getAccountBalances()
            .first()
            .filter { it.accountId == binance.id }
            .associate { it.balance.asset.code to it.balance.toDisplayValue().toString() }
    }

    private suspend fun tradeCount(): Int {
        val binance = assertNotNull(accountByName("Binance"))
        return repositories.tradeRepository
            .getTradesByAccount(binance.id)
            .first()
            .size
    }

    @Test
    fun theApiImportDoesNotRebookWhatTheStatementExportAlreadyRecorded() =
        runTest {
            importCsv()

            val balancesAfterCsv = binanceBalances()
            assertEquals(
                mapOf(
                    "BTC" to "0.31",
                    "USDT" to "-12012",
                    "XMR" to "-5.81",
                    "LINK" to "-82.358",
                    "GBP" to "-1134.83",
                ),
                balancesAfterCsv,
                "the export alone: 0.01 deposited + 0.3 bought, 12000 spent + 12 fees, 5.81 into Earn, " +
                    "and two withdrawals booked gross",
            )
            assertEquals(1, tradeCount(), "the export folds both fills into one trade")

            importApi()

            assertEquals(
                balancesAfterCsv,
                binanceBalances(),
                "every API movement was already in the export, so no balance may move",
            )
            assertEquals(1, tradeCount(), "the API's two fills reconcile against the export's aggregate")
        }

    @Test
    fun theApiSupersedesThePlaceholderCounterpartyTheExportLeftForADeposit() =
        runTest {
            importCsv()
            assertEquals("-0.01", balanceOf("Binance Funding", "BTC"), "the export can only name a placeholder")

            importApi()

            // The API knows the address, so its leg is the record kept and the placeholder is excluded -
            // the deposit stays counted once, now against the wallet it actually came from. (The
            // placeholder row itself survives; it just no longer contributes, so the account drops out of
            // the balance view entirely.)
            assertEquals(null, balanceOf("Binance Funding", "BTC"), "the placeholder leg no longer counts")
            val wallet = assertNotNull(accountByName("BTC:bc1qdepositaddr"), "the API books the on-chain address")
            assertEquals("-0.01", balanceOf(wallet.name, "BTC"))
            assertEquals("0.31", balanceOf("Binance", "BTC"), "the deposit is still counted exactly once")
        }

    @Test
    fun aWithdrawalTheApiReportsNetOfItsFeeReconcilesAgainstTheExportsGrossRow() =
        runTest {
            importCsv()
            // The export folds the charge into one row and can only name a placeholder for the far end.
            assertEquals("1134.83", balanceOf("Binance Bank", "GBP"))
            assertEquals("82.358", balanceOf("Binance Funding", "LINK"))

            importApi()

            // Nothing moved: the API's net leg plus its fee leg total exactly the gross row it replaced.
            assertEquals("-1134.83", balanceOf("Binance", "GBP"), "the fiat withdrawal is counted once")
            assertEquals("-82.358", balanceOf("Binance", "LINK"), "the crypto withdrawal is counted once")
            // ... and it is now recorded the better way, with the charge and the destination named.
            assertEquals("1133.33", balanceOf("Binance Bank", "GBP"))
            assertEquals("1.5", balanceOf("Binance Fees", "GBP"), "totalFee is booked, not dropped")
            assertEquals("1.25", balanceOf("Binance Fees", "LINK"))
            val wallet = assertNotNull(accountByName("ETH:0xwithdrawaddr"), "the API names where it went")
            assertEquals("81.108", balanceOf(wallet.name, "LINK"))
            assertEquals(null, balanceOf("Binance Funding", "LINK"), "the gross placeholder leg no longer counts")
        }

    @Test
    fun reconciledMovementsAreKeptAndLinkedRatherThanDropped() =
        runTest {
            importCsv()
            importApi()

            val binance = assertNotNull(accountByName("Binance"))
            val transfers =
                repositories.transactionRepository
                    .getTransactionsByAccount(binance.id)
                    .first()
            val links = repositories.transferRelationshipRepository.getByTransfers(transfers.map { it.id })
            val reconciled = links.filter { it.relationshipType.id.id == WellKnownIds.RECONCILED_RELATIONSHIP_TYPE_ID }
            // Deposit, Earn subscription, two trade fees and the two gross-vs-net withdrawals.
            assertEquals(6, reconciled.size, "every duplicated transfer is kept and linked to its twin")
            assertTrue(
                transfers.any { t -> t.attributes.any { it.attributeType.id.id == WellKnownIds.EXCLUDED_ATTR_TYPE_ID } },
                "a reconciled record is kept but excluded from balances, never silently dropped",
            )
        }
}
