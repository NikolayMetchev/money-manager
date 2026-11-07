package com.moneymanager.database.csv

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.csvimporter.bulkApplyCsv
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.Trade
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.createAccount
import com.moneymanager.importengineapi.setReconciliationLinks
import com.moneymanager.reconciliation.reconcile
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * End-to-end cover for the built-in Accointing strategy, a reconciliation source like Koinly: its rows land only on
 * shadow accounts, and overlapping exports dedupe by Accointing's id.
 */
class AccointingReconciliationE2ETest : DbTest() {
    override val installBuiltInStrategies: Boolean = true

    private val now = Clock.System.now()

    private val headers =
        listOf(
            "timeExecuted",
            "type",
            "boughtQuantity",
            "boughtCurrency",
            "boughtCurrencyId",
            "soldQuantity",
            "soldCurrency",
            "soldCurrencyId",
            "feeQuantity",
            "feeCurrency",
            "feeCurrencyId",
            "classification",
            "walletName",
            "walletProvider",
            "txId",
            "primaryAddress",
            "otherAddress",
            "temporaryCurrencyName",
            "temporaryFeeCurrencyName",
            "temporaryBoughtCurrencyTicker",
            "temporarySoldCurrencyTicker",
            "temporaryFeeCurrencyTicker",
            "id",
            "associatedTransferId",
            "comments",
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
        val byName = cells.toMap()
        return headers.map { byName[it].orEmpty() }
    }

    private val income =
        row(
            "timeExecuted" to "1/7/2021 10:59:50",
            "type" to "deposit",
            "boughtQuantity" to "0.5",
            "boughtCurrency" to "ADA",
            "feeQuantity" to "0",
            "classification" to "income",
            "walletName" to "Binance",
            "id" to "1",
        )
    private val midnightIncome =
        row(
            "timeExecuted" to "2/7/2021",
            "type" to "deposit",
            "boughtQuantity" to "0.25",
            "boughtCurrency" to "ADA",
            "feeQuantity" to "0",
            "classification" to "income",
            "walletName" to "Binance",
            "id" to "2",
        )
    private val order =
        row(
            "timeExecuted" to "3/7/2021 08:00:00",
            "type" to "order",
            "boughtQuantity" to "1",
            "boughtCurrency" to "ETH",
            "soldQuantity" to "2000",
            "soldCurrency" to "USD",
            "feeQuantity" to "5",
            "feeCurrency" to "USD",
            "walletName" to "Binance",
            "id" to "3",
        )
    private val pairOut =
        row(
            "timeExecuted" to "4/7/2021 09:00:00",
            "type" to "withdraw",
            "soldQuantity" to "0.75",
            "soldCurrency" to "ETH",
            "feeQuantity" to "0.01",
            "feeCurrency" to "ETH",
            "classification" to "internal",
            "walletName" to "Binance",
            "id" to "4",
            "associatedTransferId" to "5",
        )
    private val pairIn =
        row(
            "timeExecuted" to "4/7/2021 09:05:00",
            "type" to "deposit",
            "boughtQuantity" to "0.75",
            "boughtCurrency" to "ETH",
            "feeQuantity" to "0",
            "feeCurrency" to "ETH",
            "classification" to "internal",
            "walletName" to "Coinbase Wallet",
            "id" to "5",
            "associatedTransferId" to "4",
        )

    private suspend fun stage(
        fileName: String,
        rows: List<List<String>>,
        fileHeaders: List<String> = headers,
    ): CsvImport {
        val id =
            repositories.csvImportRepository.createImport(
                fileName = fileName,
                headers = fileHeaders,
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

    private suspend fun accounts(): List<Account> = repositories.accountRepository.getAllAccounts().first()

    private suspend fun transfers(): List<Transfer> =
        accounts()
            .flatMap { repositories.transactionRepository.getTransactionsByAccount(it.id).first() }
            .distinctBy { it.id }

    private suspend fun trades(): List<Trade> =
        accounts()
            .flatMap {
                repositories.tradeRepository.getTradesByAccount(it.id).first()
            }.distinctBy { it.id }

    private suspend fun accountName(id: AccountId) = accounts().single { it.id == id }.name

    private suspend fun accountId(name: String) = accounts().single { it.name == name }.id

    private fun List<Transfer>.byAmount(amount: String) = single { it.amount.toDisplayValue().toString() == amount }

    @Test
    fun `every account is a shadow of the source`() =
        runDbTest {
            val result = applyAll(listOf(stage("history.xlsx", listOf(income, midnightIncome, order, pairOut, pairIn))))
            assertEquals(1, result.filesImported)

            assertEquals(
                setOf(
                    "Accointing · Binance",
                    "Accointing · Coinbase Wallet",
                    "Accointing: income",
                    "Accointing: internal",
                    "Accointing Fees",
                ),
                accounts().map { it.name }.toSet(),
            )
            assertEquals(
                accounts().map { it.id }.toSet(),
                repositories.accountRepository.getAccountIdsByAttribute(
                    WellKnownIds.ACCOUNT_RECONCILIATION_SOURCE_ATTR_TYPE_ID,
                    "Accointing",
                ),
            )
        }

    @Test
    fun `rows read as UTC whether or not their cell shows a time`() =
        runDbTest {
            applyAll(listOf(stage("history.xlsx", listOf(income, midnightIncome))))

            val all = transfers()
            assertEquals(Instant.parse("2021-07-01T10:59:50Z"), all.byAmount("0.5").timestamp)
            assertEquals(Instant.parse("2021-07-02T00:00:00Z"), all.byAmount("0.25").timestamp)
        }

    @Test
    fun `a deposit comes from its classification and a withdrawal goes to it`() =
        runDbTest {
            applyAll(listOf(stage("history.xlsx", listOf(income, pairOut, pairIn))))

            val all = transfers()
            val deposit = all.byAmount("0.5")
            assertEquals("Accointing: income", accountName(deposit.sourceAccountId))
            assertEquals("Accointing · Binance", accountName(deposit.targetAccountId))
            val movements = all.filter { it.amount.toDisplayValue().toString() == "0.75" }
            assertEquals(
                setOf(
                    "Accointing · Binance" to "Accointing: internal",
                    "Accointing: internal" to "Accointing · Coinbase Wallet",
                ),
                movements.map { accountName(it.sourceAccountId) to accountName(it.targetAccountId) }.toSet(),
            )
        }

    @Test
    fun `an order is a trade on its wallet with its fee booked apart`() =
        runDbTest {
            applyAll(listOf(stage("history.xlsx", listOf(order))))

            val trade = trades().single()
            assertEquals("Accointing · Binance", accountName(trade.fromAccountId))
            assertEquals(trade.fromAccountId, trade.toAccountId)
            assertEquals("USD", trade.from.asset.code)
            assertEquals("2000", trade.from.toDisplayValue().toString())
            assertEquals("ETH", trade.to.asset.code)
            assertEquals("1", trade.to.toDisplayValue().toString())
            val fee = transfers().single()
            assertEquals("Accointing Fees", accountName(fee.targetAccountId))
            assertEquals("5", fee.amount.toDisplayValue().toString())
        }

    @Test
    fun `a withdrawal of nothing but its fee is that one movement`() =
        runDbTest {
            val feeOnly =
                row(
                    "timeExecuted" to "7/7/2021 10:00:00",
                    "type" to "withdraw",
                    "soldQuantity" to "0",
                    "soldCurrency" to "ETH",
                    "feeQuantity" to "0.0028",
                    "feeCurrency" to "ETH",
                    "classification" to "fee",
                    "walletName" to "Coinbase Wallet",
                    "id" to "8",
                )

            val result = applyAll(listOf(stage("history.xlsx", listOf(feeOnly))))

            assertEquals(1, result.filesImported)
            val legs = repositories.reconciliationLinkRepository.getLegs(setOf(accountId("Accointing · Coinbase Wallet")))
            assertEquals(
                listOf("0.0028"),
                legs.map {
                    it.amount
                        .toDisplayValue()
                        .toString()
                        .removePrefix("-")
                },
            )
            assertEquals("Accointing: fee", accountName(transfers().single().targetAccountId))
        }

    @Test
    fun `an export with the newer providerId column selects the same strategy and dedupes by id`() =
        runDbTest {
            applyAll(listOf(stage("history (1).xlsx", listOf(income))))
            val newer =
                stage(
                    "history.xlsx",
                    listOf(income + "22", midnightIncome + "22"),
                    fileHeaders = headers + "providerId",
                )

            val result = applyAll(listOf(newer))

            assertEquals(1, result.filesImported)
            assertEquals(listOf("0.25", "0.5"), transfers().map { it.amount.toDisplayValue().toString() }.sorted())
        }

    @Test
    fun `an order of an asset for the same amount of itself moves nothing`() =
        runDbTest {
            val noOp =
                row(
                    "timeExecuted" to "8/7/2021 06:32:27",
                    "type" to "order",
                    "boughtQuantity" to "57.53",
                    "boughtCurrency" to "USD",
                    "soldQuantity" to "57.53",
                    "soldCurrency" to "USD",
                    "feeQuantity" to "0",
                    "walletName" to "Binance",
                    "id" to "9",
                )

            val result = applyAll(listOf(stage("history.xlsx", listOf(income, noOp))))

            assertEquals(1, result.filesImported)
            assertEquals(listOf("0.5"), transfers().map { it.amount.toDisplayValue().toString() })
            assertTrue(trades().isEmpty())
        }

    @Test
    fun `a row ignored in Accointing is imported excluded`() =
        runDbTest {
            val ignored =
                row(
                    "timeExecuted" to "5/7/2021 10:00:00",
                    "type" to "deposit",
                    "boughtQuantity" to "150000",
                    "boughtCurrency" to "ADA",
                    "feeQuantity" to "0",
                    "classification" to "ignored",
                    "walletName" to "Binance",
                    "id" to "6",
                )

            applyAll(listOf(stage("history.xlsx", listOf(ignored))))

            val leg = repositories.reconciliationLinkRepository.getLegs(setOf(accountId("Accointing · Binance"))).single()
            assertTrue(leg.isExcluded)
        }

    @Test
    fun `an unknown asset takes its own name instead of the placeholder ticker`() =
        runDbTest {
            val unknown =
                row(
                    "timeExecuted" to "6/7/2021 10:00:00",
                    "type" to "deposit",
                    "boughtQuantity" to "1.5",
                    "boughtCurrency" to "???",
                    "feeQuantity" to "0",
                    "classification" to "swap",
                    "walletName" to "Binance",
                    "temporaryCurrencyName" to "Compound Collateral",
                    "id" to "7",
                )

            applyAll(listOf(stage("history.xlsx", listOf(unknown))))

            assertEquals(
                "COMPOUND COLLATERAL",
                transfers()
                    .single()
                    .amount.asset.code,
            )
        }

    @Test
    fun `the two rows of an internal transfer are linked`() =
        runDbTest {
            applyAll(listOf(stage("history.xlsx", listOf(pairIn, pairOut))))

            val movements = transfers().filter { it.amount.toDisplayValue().toString() == "0.75" }
            val links = repositories.relationshipRepository.getByTransactions(movements.map { it.id })
            val pair = assertNotNull(links.single { it.relationshipType.name == "transfer pair" }.transferPair)
            assertEquals(movements.map { it.id }.toSet(), setOf(pair.first, pair.second))
        }

    @Test
    fun `its legs match the linked real account to the second`() =
        runDbTest {
            applyAll(listOf(stage("history.xlsx", listOf(income))))
            val rewards =
                repositories.importEngine.createAccount(
                    Account(id = AccountId(0), name = "Rewards", openingDate = now),
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
                                timestamp = Instant.parse("2021-07-01T10:59:50Z"),
                                amount = Money.fromDisplayValue(BigDecimal("0.5"), ada),
                            ),
                        ),
                ),
            )
            val wallet = accountId("Accointing · Binance")
            repositories.importEngine.setReconciliationLinks(wallet, setOf(binance))

            val reconciliation = repositories.reconciliationLinkRepository
            val links = reconciliation.getLinks().first()
            val result =
                reconcile(
                    sourceLegs = reconciliation.getLegs(setOf(wallet)),
                    links = links,
                    realLegs = reconciliation.getLegs(setOf(binance)),
                )

            assertEquals(1, result.matched.size)
            assertTrue(result.missingInMm.isEmpty())
            assertTrue(result.missingInSource.isEmpty())
            assertTrue("Accointing" in reconciliation.getSources().first().map { it.name })
        }
}
