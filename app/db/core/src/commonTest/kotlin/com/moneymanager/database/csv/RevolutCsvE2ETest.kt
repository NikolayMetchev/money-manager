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
import com.moneymanager.domain.model.reconciliation.ReportedBalance
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.ValueExpr
import com.moneymanager.importengineapi.ManualTradeInput
import com.moneymanager.importengineapi.createManualTradeEntries
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.reimportFile
import com.moneymanager.test.database.reimports
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * End-to-end cover for the built-in Revolut strategy: the two sides of a currency exchange sit in two currencies'
 * statements, and become one trade on the Revolut account whichever statement is applied first.
 */
class RevolutCsvE2ETest : DbTest() {
    override val installBuiltInStrategies: Boolean = true

    private val now = Clock.System.now()

    private val headers =
        listOf("Type", "Product", "Started Date", "Completed Date", "Description", "Amount", "Fee", "Currency", "State", "Balance")

    private val maintenance =
        object : Maintenance {
            override suspend fun reindex(): Duration = Duration.ZERO

            override suspend fun vacuum(): Duration = Duration.ZERO

            override suspend fun analyze(): Duration = Duration.ZERO

            override suspend fun refreshMaterializedViews(): Duration = Duration.ZERO

            override suspend fun fullRefreshMaterializedViews(): Duration = Duration.ZERO
        }

    private fun row(
        type: String,
        time: String,
        description: String,
        amount: String,
        currency: String,
        balance: String,
        fee: String = "0.00",
    ): List<String> = listOf(type, "Current", time, time, description, amount, fee, currency, "COMPLETED", balance)

    private val eurFile =
        listOf(
            row("Card Payment", "2020-05-30 15:23:24", "Deichmann", "-22.35", "EUR", "977.65"),
            row("Exchange", "2020-06-01 09:07:19", "Exchanged to USD", "-200.00", "EUR", "777.65"),
        )
    private val usdFile =
        listOf(
            row("Exchange", "2020-06-01 09:07:19", "Exchanged to USD", "230.00", "USD", "230.00"),
            row("ATM", "2020-06-02 10:00:00", "Cash withdrawal at Atm", "-50.00", "USD", "180.00", fee = "1.50"),
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
                fileChecksum = fileName,
                fileLastModified = now,
            )
        return repositories.csvImportRepository.getImport(id).first()!!
    }

    private suspend fun apply(vararg imports: CsvImport) =
        bulkApplyCsv(
            imports = imports.toList(),
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
        repositories.tradeRepository.getTradesByAccountsAndDateRange(
            accountIds = accounts().map { it.id }.toSet(),
            minTimestamp = Instant.parse("2000-01-01T00:00:00Z"),
            maxTimestamp = Instant.parse("2100-01-01T00:00:00Z"),
        )

    private suspend fun assertOneExchangeTrade() {
        val trade = trades().single()
        val revolut = accounts().single { it.name == "Revolut" }
        assertEquals(revolut.id, trade.fromAccountId)
        assertEquals(revolut.id, trade.toAccountId)
        assertEquals("EUR", trade.from.asset.code)
        assertEquals(0, trade.from.toDisplayValue().compareTo(BigDecimal("200")))
        assertEquals("USD", trade.to.asset.code)
        assertEquals(0, trade.to.toDisplayValue().compareTo(BigDecimal("230")))
        // The exchange rows are the trade, so no transfer repeats them.
        assertEquals(false, transfers().any { it.description.startsWith("Exchanged to") })
    }

    @Test
    fun `an exchange waits for its other currency then books one trade on the Revolut account`() =
        runDbTest {
            val eur = stage("account-statement_eur.csv", eurFile)
            apply(eur)
            assertEquals(emptyList(), trades(), "a lone exchange leg is held back, not booked")
            assertEquals(false, transfers().any { it.description.startsWith("Exchanged to") })

            val usd = stage("account-statement_usd.csv", usdFile)
            apply(usd)
            assertOneExchangeTrade()
        }

    @Test
    fun `applying the second statement first and then the first still books exactly one trade`() =
        runDbTest {
            val usd = stage("account-statement_usd.csv", usdFile)
            apply(usd)
            assertEquals(emptyList(), trades())
            val eur = stage("account-statement_eur.csv", eurFile)
            apply(eur)
            assertOneExchangeTrade()
            // Re-applying either file is a no-op: the trade dedupes onto itself.
            apply(usd)
            apply(eur)
            assertEquals(1, trades().size)
        }

    @Test
    fun `two exports of the same period are one exchange not two`() =
        runDbTest {
            val eur = stage("account-statement_eur.csv", eurFile)
            stage("account-statement_eur_again.csv", eurFile)
            val usd = stage("account-statement_usd.csv", usdFile)
            apply(usd)
            assertOneExchangeTrade()
            apply(eur)
            assertEquals(1, trades().size)
        }

    @Test
    fun `two exchanges a second apart in opposite directions each pair with their own other side`() =
        runDbTest {
            val eur =
                stage(
                    "account-statement_eur.csv",
                    eurFile + listOf(row("Exchange", "2020-06-01 09:07:20", "Exchanged to EUR", "50.00", "EUR", "827.65")),
                )
            val usd =
                stage(
                    "account-statement_usd.csv",
                    usdFile + listOf(row("Exchange", "2020-06-01 09:07:20", "Exchanged to EUR", "-57.00", "USD", "123.00")),
                )
            apply(usd, eur)
            assertEquals(2, trades().size)
        }

    @Test
    fun `an exchange that did not complete is neither a trade nor an error`() =
        runDbTest {
            val reverted = { currency: String, amount: String ->
                row("Exchange", "2020-06-01 09:07:19", "Exchanged to USD", amount, currency, "0.00").dropLast(2) +
                    listOf("REVERTED", "")
            }
            val eur = stage("account-statement_eur.csv", listOf(reverted("EUR", "-200.00")))
            val usd = stage("account-statement_usd.csv", listOf(reverted("USD", "230.00")))
            apply(eur, usd)
            assertEquals(emptyList(), trades())
            assertEquals(emptyList(), repositories.csvImportRepository.getImportRows(eur.id, 10, 0).filter { it.errorMessage != null })
        }

    private suspend fun pendingManualTrades(): List<Trade> =
        repositories.tradeRepository.getPendingManualTrades(WellKnownIds.MANUAL_ENTRY_ATTR_TYPE_NAME, "UNKNOWN").first()

    private val unnamedOut = row("Exchange", "2020-07-01 10:00:00", "Transfer to Revolut Ltd", "-300.00", "EUR", "477.65")
    private val unnamedIn = row("Exchange", "2020-07-01 10:00:00", "Transfer from Revolut Ltd", "350.00", "USD", "530.00")

    @Test
    fun `an exchange row that names no currency books against Unknown and waits in Manual Entries`() =
        runDbTest {
            apply(stage("account-statement_eur.csv", listOf(unnamedOut)))

            val placeholder = pendingManualTrades().single()
            assertEquals("EUR", placeholder.from.asset.code)
            assertEquals("UNKNOWN", placeholder.to.asset.code)
            assertEquals(0, placeholder.from.toDisplayValue().compareTo(BigDecimal("300")))
            assertEquals(false, transfers().any { it.description == "Transfer to Revolut Ltd" })
        }

    @Test
    fun `the other side of an unnamed exchange replaces the placeholder and leaves Manual Entries`() =
        runDbTest {
            apply(stage("account-statement_eur.csv", listOf(unnamedOut)))
            assertEquals(1, pendingManualTrades().size)

            apply(stage("account-statement_usd.csv", listOf(unnamedIn)))

            assertEquals(emptyList(), pendingManualTrades())
            val real = trades().single { it.to.asset.code == "USD" }
            assertEquals("EUR", real.from.asset.code)
            assertEquals(0, real.to.toDisplayValue().compareTo(BigDecimal("350")))
        }

    @Test
    fun `a person naming the currency and amount replaces the placeholder`() =
        runDbTest {
            apply(stage("account-statement_eur.csv", listOf(unnamedOut)))
            val placeholder = pendingManualTrades().single()
            val usd =
                repositories.currencyRepository
                    .getAllCurrencies()
                    .first()
                    .single { it.code == "USD" }

            repositories.importEngine.createManualTradeEntries(
                listOf(ManualTradeInput(placeholder, "UNKNOWN", Money.fromDisplayValue(BigDecimal("350"), usd))),
            )

            assertEquals(emptyList(), pendingManualTrades())
            val real = trades().single { it.to.asset.code == "USD" }
            assertEquals(0, real.to.toDisplayValue().compareTo(BigDecimal("350")))
            assertEquals(0, real.from.toDisplayValue().compareTo(BigDecimal("300")))
        }

    @Test
    fun `a statement leg retires the trade a person entered for its placeholder too`() =
        runDbTest {
            apply(stage("account-statement_eur.csv", listOf(unnamedOut)))
            val usd =
                repositories.currencyRepository
                    .getAllCurrencies()
                    .first()
                    .single { it.code == "USD" }
            repositories.importEngine.createManualTradeEntries(
                listOf(ManualTradeInput(pendingManualTrades().single(), "UNKNOWN", Money.fromDisplayValue(BigDecimal("345"), usd))),
            )

            apply(stage("account-statement_usd.csv", listOf(unnamedIn)))

            val counted = repositories.tradeRepository.getManualEntryTrades(WellKnownIds.MANUAL_ENTRY_ATTR_TYPE_NAME).first()
            assertEquals(emptyList(), counted, "neither the placeholder nor the manual trade still counts")
            assertEquals(
                0,
                trades()
                    .single {
                        it.id !in counted.map { c -> c.id } && it.to.asset.code == "USD" &&
                            it.description.isNotEmpty() &&
                            it.to.toDisplayValue().compareTo(BigDecimal("350")) == 0
                    }.from
                    .toDisplayValue()
                    .compareTo(BigDecimal("300")),
            )
        }

    @Test
    fun `a manual entry cannot name the placeholder or the known currency`() =
        runDbTest {
            apply(stage("account-statement_eur.csv", listOf(unnamedOut)))
            val placeholder = pendingManualTrades().single()
            val currencies = repositories.currencyRepository.getAllCurrencies().first()
            for (code in listOf("UNKNOWN", "EUR")) {
                val currency = currencies.single { it.code == code }
                assertFailsWith<IllegalArgumentException> {
                    repositories.importEngine.createManualTradeEntries(
                        listOf(ManualTradeInput(placeholder, "UNKNOWN", Money.fromDisplayValue(BigDecimal("300"), currency))),
                    )
                }
            }
            assertEquals(1, pendingManualTrades().size)
        }

    @Test
    fun `the fee on an unnamed exchange row is still booked`() =
        runDbTest {
            apply(
                stage(
                    "account-statement_eur.csv",
                    listOf(row("Exchange", "2020-07-01 10:00:00", "Transfer to Revolut Ltd", "-300.00", "EUR", "476.15", fee = "1.50")),
                ),
            )

            val fee = transfers().single { it.amount.toDisplayValue().compareTo(BigDecimal("1.5")) == 0 }
            assertEquals("EUR", fee.amount.asset.code)
        }

    private suspend fun reportedBalances(currency: String): List<ReportedBalance> {
        val revolut = accounts().single { it.name == "Revolut" }
        val asset =
            repositories.currencyRepository
                .getAllCurrencies()
                .first()
                .single { it.code == currency }
        return repositories.reportedBalanceRepository.getBalances(revolut.id, asset)
    }

    private fun List<ReportedBalance>.amounts(): List<Money> = map { it.amount }

    private suspend fun money(
        vararg amounts: String,
        currency: String,
    ): List<Money> {
        val asset =
            repositories.currencyRepository
                .getAllCurrencies()
                .first()
                .single { it.code == currency }
        return amounts.map { Money.fromDisplayValue(BigDecimal(it), asset) }
    }

    @Test
    fun `every row's balance is kept against the Revolut account with its row as the source`() =
        runDbTest {
            val eur = stage("account-statement_eur.csv", eurFile)
            val usd = stage("account-statement_usd.csv", usdFile)
            apply(eur, usd)

            // The exchange legs became one trade, but their balances are each statement's own.
            assertEquals(money("977.65", "777.65", currency = "EUR"), reportedBalances("EUR").amounts())
            assertEquals(money("230", "180", currency = "USD"), reportedBalances("USD").amounts())
            val sources =
                reportedBalances("EUR").map {
                    repositories.reportedBalanceRepository
                        .getSources(it.id)
                        .single()
                        .source
                }
            assertEquals(listOf(1L, 2L), sources.map { (it as Source.Csv).rowIndex })
            assertEquals(setOf(eur.id), sources.map { (it as Source.Csv).importId }.toSet())
            assertEquals(
                listOf("Revolut"),
                repositories.reportedBalanceRepository
                    .getSeries()
                    .first()
                    .map { it.accountName }
                    .distinct(),
            )
        }

    @Test
    fun `a re-import keeps unchanged balances and revises changed ones`() =
        runDbTest {
            val eur = stage("account-statement_eur.csv", eurFile)
            apply(eur, stage("account-statement_usd.csv", usdFile))
            val before = reportedBalances("EUR")
            val reimports = repositories.reimports(maintenance)

            reimports.reimportFile(eur.id)
            assertEquals(before, reportedBalances("EUR"))

            // A strategy whose balance reads another column changes every balance the file reports.
            val appliedId =
                repositories.csvImportRepository
                    .getImport(eur.id)
                    .first()!!
                    .lastAppliedStrategyId
            val revolut =
                repositories.csvImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .single { it.id == appliedId }
            val readsAmount =
                revolut.copy(
                    config =
                        revolut.config.copy(
                            rules =
                                revolut.config.rules.map { rule ->
                                    rule.copy(
                                        actions =
                                            rule.actions.map {
                                                if (it is RuleAction.Balance) {
                                                    RuleAction.Balance(
                                                        ValueExpr.of("Amount"),
                                                    )
                                                } else {
                                                    it
                                                }
                                            },
                                    )
                                },
                        ),
                )
            reimports.reimportFile(eur.id, strategy = readsAmount)
            val after = reportedBalances("EUR")
            assertEquals(money("-22.35", "-200", currency = "EUR"), after.amounts())
            assertEquals(before.map { it.id }, after.map { it.id })
            assertTrue(after.zip(before).all { (now, then) -> now.revisionId > then.revisionId })
            // Each revision keeps its old value in the audit history, and every one names the row that reported it.
            val history = repositories.auditRepository.getAuditHistoryForReportedBalance(after.first().id)
            assertEquals(after.first().revisionId, history.maxOf { it.revisionId })
            assertTrue(history.size >= 2)
            assertTrue(history.all { it.source?.source is Source.Csv }, "every revision has its row as the source: $history")

            // Without a balance rule the file reports none.
            val withoutBalance =
                revolut.copy(
                    config =
                        revolut.config.copy(
                            rules =
                                revolut.config.rules.filter { rule ->
                                    rule.actions.none { it is RuleAction.Balance }
                                },
                        ),
                )
            reimports.reimportFile(eur.id, strategy = withoutBalance)
            assertEquals(emptyList(), reportedBalances("EUR"))
            assertEquals(2, reportedBalances("USD").size)
        }

    @Test
    fun `merging the Revolut account moves its balances, and undoing the merge moves them back`() =
        runDbTest {
            apply(stage("account-statement_eur.csv", eurFile))
            val revolut = accounts().single { it.name == "Revolut" }
            val eur =
                repositories.currencyRepository
                    .getAllCurrencies()
                    .first()
                    .single { it.code == "EUR" }
            val other =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "My Revolut", openingDate = now),
                    Source.Manual,
                )
            val balances = repositories.reportedBalanceRepository

            val merge = repositories.accountRepository.mergeAccounts(deletedAccount = revolut.id, survivingAccount = other)
            assertEquals(2, balances.getBalances(other, eur).size)
            assertEquals(emptyList(), balances.getBalances(revolut.id, eur))

            repositories.accountRepository.unmergeAccount(merge)
            assertEquals(2, balances.getBalances(revolut.id, eur).size)
            assertEquals(emptyList(), balances.getBalances(other, eur))
        }
}
