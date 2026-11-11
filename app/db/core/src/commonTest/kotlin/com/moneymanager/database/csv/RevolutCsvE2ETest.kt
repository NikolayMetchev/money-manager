package com.moneymanager.database.csv

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.csvimporter.bulkApplyCsv
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Trade
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.importengineapi.ManualTradeInput
import com.moneymanager.importengineapi.createManualTradeEntries
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
}
