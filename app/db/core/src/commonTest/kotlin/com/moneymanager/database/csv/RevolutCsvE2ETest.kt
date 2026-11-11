package com.moneymanager.database.csv

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.csvimporter.bulkApplyCsv
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.Trade
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
