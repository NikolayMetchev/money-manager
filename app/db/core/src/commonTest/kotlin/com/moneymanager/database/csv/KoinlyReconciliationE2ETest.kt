@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.csv

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.csvimporter.bulkApplyCsv
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.createAccount
import com.moneymanager.importengineapi.setReconciliationLinks
import com.moneymanager.importengineapi.setTradeExcluded
import com.moneymanager.reconciliation.reconcile
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * End-to-end cover for the built-in Koinly strategy — a reconciliation source. Koinly rows must land
 * only on shadow accounts (so they never count towards real balances nor take part in real imports'
 * dedupe), overlapping exports must dedupe like any CSV import, and the reconciliation read side must
 * match them against the real accounts their wallets are linked to.
 */
class KoinlyReconciliationE2ETest : DbTest() {
    override val installBuiltInStrategies: Boolean = true

    private val now = Clock.System.now()

    private val headers =
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

    private fun row(vararg cells: Pair<String, String>): List<String> {
        val byName = cells.toMap() + ("Deleted" to "false")
        return headers.map { byName[it].orEmpty() }
    }

    private val binanceReward =
        row(
            "ID (read-only)" to "K1",
            "Date (UTC)" to "2021-05-15 00:56:32",
            "Type" to "deposit",
            "Tag" to "reward",
            "To Wallet (read-only)" to "Binance;binance",
            "To Amount" to "0.00051544",
            "To Currency" to "ADA;1865",
            "From Amount" to "0.0",
            "Fee Amount" to "0.0",
        )
    private val coinbaseDeposit =
        row(
            "ID (read-only)" to "K2",
            "Date (UTC)" to "2020-06-01 10:00:00",
            "Type" to "deposit",
            "To Wallet (read-only)" to "Coinbase;coinbase",
            "To Amount" to "1000.0",
            "To Currency" to "GBP;12",
        )
    private val coinbaseTrade =
        row(
            "ID (read-only)" to "K3",
            "Date (UTC)" to "2020-07-03 08:22:32",
            "Type" to "trade",
            "From Wallet (read-only)" to "Coinbase;coinbase",
            "From Amount" to "492.66",
            "From Currency" to "GBP;12",
            "To Wallet (read-only)" to "Coinbase;coinbase",
            "To Amount" to "358.32414347",
            "To Currency" to "KNCL;1841",
            "Fee Amount" to "7.34",
            "Fee Currency" to "GBP;12",
        )
    private val walletTransfer =
        row(
            "ID (read-only)" to "K4",
            "Date (UTC)" to "2021-06-23 10:02:14",
            "Type" to "transfer",
            "From Wallet (read-only)" to "Coinbase Pro;gdax",
            "From Amount" to "47.0",
            "From Currency" to "AMP;9984",
            "To Wallet (read-only)" to "Coinbase;coinbase",
            "To Amount" to "47.0",
            "To Currency" to "AMP;9984",
            "Fee Amount" to "0.5",
            "Fee Currency" to "BNB;1749",
        )
    private val withdrawal =
        row(
            "ID (read-only)" to "K5",
            "Date (UTC)" to "2020-07-31 23:11:35",
            "Type" to "withdrawal",
            "From Wallet (read-only)" to "Coinbase;coinbase",
            "From Amount" to "100.0",
            "From Currency" to "GBP;12",
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
            onProgress = { },
            cryptoRepository = repositories.cryptoRepository,
            tradeRepository = repositories.tradeRepository,
        )

    private suspend fun accountByName(name: String) =
        repositories.accountRepository
            .getAllAccounts()
            .first()
            .firstOrNull { it.name == name }

    private suspend fun shadowIds(): Set<AccountId> =
        repositories.accountRepository.getAccountIdsByAttribute(WellKnownIds.ACCOUNT_RECONCILIATION_SOURCE_ATTR_TYPE_ID, "Koinly")

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
    fun everyKoinlyRowLandsOnShadowAccounts() =
        runTest {
            val result =
                applyAll(
                    listOf(stage("transactions.csv", listOf(binanceReward, coinbaseDeposit, coinbaseTrade, walletTransfer, withdrawal))),
                )
            assertEquals(1, result.filesImported)

            val shadow = shadowIds()
            val accounts = repositories.accountRepository.getAllAccounts().first()
            // Nothing outside the shadow set exists: Koinly created no real accounts at all.
            assertEquals(accounts.map { it.id }.toSet(), shadow)
            assertEquals(
                setOf(
                    "Koinly · Binance",
                    "Koinly · Coinbase",
                    "Koinly · Coinbase Pro",
                    "Koinly: reward",
                    "Koinly: External",
                    "Koinly Fees",
                ),
                accounts.map { it.name }.toSet(),
            )

            assertEquals("0.00051544", balanceOf("Koinly · Binance", "ADA"))
            // 1000 deposited - 492.66 traded - 7.34 fee - 100 withdrawn.
            assertEquals("400", balanceOf("Koinly · Coinbase", "GBP"))
            // Koinly's legacy KNCL ticker is aliased onto KNC.
            assertEquals("358.32414347", balanceOf("Koinly · Coinbase", "KNC"))
            assertEquals("47", balanceOf("Koinly · Coinbase", "AMP"))
            // The transfer's fee was paid in a third asset.
            assertEquals("-0.5", balanceOf("Koinly · Coinbase Pro", "BNB"))
            assertEquals("0.5", balanceOf("Koinly Fees", "BNB"))
            assertNotNull(repositories.cryptoRepository.getCryptoAssetByCode("KNC").first())
            assertNull(repositories.cryptoRepository.getCryptoAssetByCode("KNCL").first())
        }

    private fun List<String>.deleted(): List<String> = headers.zip(this).map { (name, value) -> if (name == "Deleted") "true" else value }

    @Test
    fun deletedRowsAreImportedButExcluded() =
        runTest {
            applyAll(listOf(stage("transactions.csv", listOf(coinbaseDeposit, coinbaseTrade.deleted(), walletTransfer.deleted()))))

            // Only the live deposit counts: the deleted trade, its fee and the deleted transfer (with its
            // fee in a third asset) are all present but excluded.
            assertEquals("1000", balanceOf("Koinly · Coinbase", "GBP"))
            assertNull(balanceOf("Koinly · Coinbase", "KNC"))
            assertNull(balanceOf("Koinly · Coinbase", "AMP"))
            assertNull(balanceOf("Koinly Fees", "BNB"))
            val coinbase = assertNotNull(accountByName("Koinly · Coinbase")).id
            assertEquals(1, repositories.tradeRepository.countTradesByAccount(coinbase))
            // Deposit (live); trade debit + credit, trade fee, incoming transfer (all excluded).
            val legs = repositories.reconciliationLinkRepository.getLegs(setOf(coinbase))
            assertEquals(1, legs.count { !it.isExcluded })
            assertEquals(4, legs.count { it.isExcluded })
        }

    @Test
    fun aLaterExportThatUndeletesRowsBringsThemBack() =
        runTest {
            applyAll(
                listOf(stage("old.csv", listOf(coinbaseDeposit, coinbaseTrade.deleted(), withdrawal.deleted(), walletTransfer.deleted()))),
            )
            assertEquals("1000", balanceOf("Koinly · Coinbase", "GBP"))
            assertNull(balanceOf("Koinly Fees", "BNB"))

            // The newer export has the trade and the withdrawal back: they're re-imported as the same
            // trade/transfer, and their exclusion is lifted.
            applyAll(listOf(stage("new.csv", listOf(coinbaseDeposit, coinbaseTrade, withdrawal, walletTransfer))))
            // The transfer's fee leg follows it back into the balances.
            assertEquals("0.5", balanceOf("Koinly Fees", "BNB"))

            assertEquals("400", balanceOf("Koinly · Coinbase", "GBP"))
            assertEquals("358.32414347", balanceOf("Koinly · Coinbase", "KNC"))
            val coinbase = assertNotNull(accountByName("Koinly · Coinbase")).id
            assertEquals(1, repositories.tradeRepository.countTradesByAccount(coinbase))
        }

    @Test
    fun aTradeCanBeExcludedAndRestoredByHand() =
        runTest {
            applyAll(listOf(stage("transactions.csv", listOf(coinbaseDeposit, coinbaseTrade))))
            val coinbase = assertNotNull(accountByName("Koinly · Coinbase")).id
            val trade =
                repositories.tradeRepository
                    .getTradesByAccount(coinbase)
                    .first()
                    .single()

            repositories.importEngine.setTradeExcluded(trade.id, "duplicate")
            assertNull(balanceOf("Koinly · Coinbase", "KNC"))
            assertEquals(
                listOf("excluded" to "duplicate"),
                repositories.tradeRepository
                    .getAttributes(trade.id)
                    .first()
                    .filter { it.attributeType.name == "excluded" }
                    .map { it.attributeType.name to it.value },
            )

            repositories.importEngine.setTradeExcluded(trade.id, null)
            assertEquals("358.32414347", balanceOf("Koinly · Coinbase", "KNC"))
        }

    @Test
    fun aRealAccountWithTheShadowNameIsNeverReused() =
        runTest {
            val real =
                repositories.importEngine.createAccount(
                    Account(id = AccountId(0), name = "Koinly · Binance", openingDate = now),
                    Source.Manual,
                )

            applyAll(listOf(stage("transactions.csv", listOf(binanceReward))))

            assertTrue(real !in shadowIds())
            assertEquals(0, repositories.accountRepository.countTransfersByAccount(real))
        }

    @Test
    fun overlappingExportsDedupe() =
        runTest {
            applyAll(listOf(stage("a.csv", listOf(binanceReward, coinbaseDeposit, coinbaseTrade))))
            applyAll(listOf(stage("b.csv", listOf(coinbaseDeposit, coinbaseTrade, withdrawal))))

            assertEquals("400", balanceOf("Koinly · Coinbase", "GBP"))
            assertEquals("358.32414347", balanceOf("Koinly · Coinbase", "KNC"))
        }

    @Test
    fun realImportsAreNotDedupedAgainstShadowData() =
        runTest {
            applyAll(listOf(stage("transactions.csv", listOf(binanceReward))))
            // The very same reward, now from Binance's own export.
            val binanceHeaders = listOf("User_ID", "UTC_Time", "Account", "Operation", "Coin", "Change", "Remark")
            val binanceFile =
                repositories.csvImportRepository.createImport(
                    fileName = "binance.csv",
                    headers = binanceHeaders,
                    rows = listOf(listOf("53064551", "2021-05-15 00:56:32", "Spot", "Staking Rewards", "ADA", "0.00051544", "")),
                    fileChecksum = "checksum-binance",
                    fileLastModified = now,
                )
            applyAll(listOf(repositories.csvImportRepository.getImport(binanceFile).first()!!))

            assertEquals("0.00051544", balanceOf("Binance", "ADA"))
            assertEquals("0.00051544", balanceOf("Koinly · Binance", "ADA"))
        }

    @Test
    fun shadowBatchesRefuseRealAccounts() =
        runTest {
            applyAll(listOf(stage("transactions.csv", listOf(binanceReward))))
            val real =
                repositories.importEngine.createAccount(
                    Account(id = AccountId(0), name = "Binance", openingDate = now),
                    Source.Manual,
                )
            val shadow = assertNotNull(accountByName("Koinly · Binance")).id
            val ada = assertNotNull(repositories.cryptoRepository.getCryptoAssetByCode("ADA").first())

            assertFailsWith<IllegalArgumentException> {
                repositories.importEngine.import(
                    ImportBatch(
                        shadowSource = "Koinly",
                        transfers =
                            listOf(
                                ImportTransfer(
                                    source = Source.Manual,
                                    fromAccount = AccountRef.Existing(shadow),
                                    toAccount = AccountRef.Existing(real),
                                    timestamp = Instant.parse("2022-01-01T00:00:00Z"),
                                    amount = Money.fromDisplayValue(BigDecimal("1"), ada),
                                ),
                            ),
                    ),
                )
            }
        }

    @Test
    fun linksAreValidatedAndDriveMatching() =
        runTest {
            applyAll(listOf(stage("transactions.csv", listOf(binanceReward, coinbaseDeposit))))
            val shadowBinance = assertNotNull(accountByName("Koinly · Binance")).id
            val shadowCoinbase = assertNotNull(accountByName("Koinly · Coinbase")).id
            val rewards =
                repositories.importEngine.createAccount(
                    Account(id = AccountId(0), name = "Binance Staking Rewards", openingDate = now),
                    Source.Manual,
                )
            val binance =
                repositories.importEngine.createAccount(
                    Account(id = AccountId(0), name = "Binance", openingDate = now),
                    Source.Manual,
                )
            val ada = assertNotNull(repositories.cryptoRepository.getCryptoAssetByCode("ADA").first())
            repositories.importEngine.import(
                ImportBatch(
                    transfers =
                        listOf(
                            ImportTransfer(
                                source = Source.Manual,
                                fromAccount = AccountRef.Existing(rewards),
                                toAccount = AccountRef.Existing(binance),
                                timestamp = Instant.parse("2021-05-15T00:56:32Z"),
                                amount = Money.fromDisplayValue(BigDecimal("0.00051544"), ada),
                            ),
                        ),
                ),
            )

            // A shadow account can't be a link target, and a real account links to one wallet per source.
            assertFailsWith<IllegalArgumentException> {
                repositories.importEngine.setReconciliationLinks(
                    shadowBinance,
                    setOf(shadowCoinbase),
                )
            }
            repositories.importEngine.setReconciliationLinks(shadowBinance, setOf(binance))
            assertFailsWith<IllegalArgumentException> { repositories.importEngine.setReconciliationLinks(shadowCoinbase, setOf(binance)) }

            val reconciliation = repositories.reconciliationLinkRepository
            val links = reconciliation.getLinks().first()
            val shadow = shadowIds()
            val result =
                reconcile(
                    sourceLegs = reconciliation.getLegs(shadow),
                    links = links,
                    realLegs = reconciliation.getLegs(links.map { it.realAccountId }.toSet()),
                )
            assertEquals(1, result.exactMatches.size)
            // The Coinbase wallet is unlinked, so its deposit is out of scope rather than "missing".
            assertTrue(result.missingInMm.isEmpty())
            assertTrue(result.missingInSource.isEmpty())
            assertEquals(listOf("Koinly"), reconciliation.getSources().first().map { it.name })
            assertNull(accountByName("Koinly · Binance")?.takeIf { it.id !in shadow })
        }
}
