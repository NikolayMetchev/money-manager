package com.moneymanager.database.csv

import com.moneymanager.csvimporter.bulkApplyCsv
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.reimports
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * End-to-end cover for the built-in Celsius strategy: rows shaped like Celsius Network's export (the spaces in its
 * header, month names and a 12-hour clock, 18 decimal places) go through the real engine.
 */
class CelsiusCsvE2ETest : DbTest() {
    override val installBuiltInStrategies: Boolean = true

    private val now = Clock.System.now()

    private val headers =
        listOf(
            "Internal id",
            " Date and time",
            " Transaction type",
            " Coin type",
            " Coin amount",
            " USD Value",
            " Original Reward Coin",
            " Reward Amount In Original Coin",
            " Confirmed",
        )

    private val maintenance =
        object : Maintenance {
            override suspend fun reindex(): Duration = Duration.ZERO

            override suspend fun vacuum(): Duration = Duration.ZERO

            override suspend fun analyze(): Duration = Duration.ZERO

            override suspend fun refreshMaterializedViews(): Duration = Duration.ZERO

            override suspend fun fullRefreshMaterializedViews(): Duration = Duration.ZERO
        }

    private fun row(
        id: String,
        time: String,
        type: String,
        coin: String,
        amount: String,
        confirmed: String = "Yes",
    ): List<String> = listOf(id, time, type, coin, amount, "1.5", if ("Reward" in type) coin else "", "", confirmed)

    private val deposit = row("1", "June 11, 2022 10:38 PM", "Transfer", "ETH", "4")
    private val reward = row("2", "July 14, 2022 12:00 AM", "Reward", "ETH", "0.000001206275885505")
    private val promo = row("3", "July 15, 2022 12:30 PM", "Promo Code Reward", "ETH", "0.5")
    private val unconfirmed = row("4", "April 28, 2022 11:28 PM", "Withdrawal", "ETH", "-3", confirmed = "No")
    private val retried = row("5", "April 28, 2022 11:56 PM", "Withdrawal", "ETH", "-3")

    private suspend fun stage(rows: List<List<String>>): CsvImport {
        val id =
            repositories.csvImportRepository.createImport(
                fileName = "transactions-export-1.csv",
                headers = headers,
                rows = rows,
                fileChecksum = "checksum",
                fileLastModified = now,
            )
        return repositories.csvImportRepository.getImport(id).first()!!
    }

    private suspend fun apply(imports: List<CsvImport>) =
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

    private suspend fun accountName(id: AccountId) = accounts().single { it.id == id }.name

    private suspend fun route(transfer: Transfer) = accountName(transfer.sourceAccountId) to accountName(transfer.targetAccountId)

    private fun List<Transfer>.at(timestamp: String) = single { it.timestamp == Instant.parse(timestamp) }

    private suspend fun celsiusBalance(): String {
        repositories.maintenanceService.refreshMaterializedViews()
        val celsius = accounts().single { it.name == "Celsius Network" }
        return repositories.transactionRepository
            .getAccountBalances()
            .first()
            .single { it.accountId == celsius.id && it.balance.asset.code == "ETH" }
            .balance
            .toDisplayValue()
            .toString()
    }

    @Test
    fun `money in and out moves on the Celsius account in UTC`() =
        runDbTest {
            val result = apply(listOf(stage(listOf(deposit, reward, promo, retried))))
            assertEquals(1, result.filesImported)

            val all = transfers()
            assertEquals(
                "Celsius Deposits" to "Celsius Network",
                route(all.at("2022-06-11T22:38:00Z")),
            )
            assertEquals(
                "Celsius Rewards" to "Celsius Network",
                route(all.at("2022-07-14T00:00:00Z")),
            )
            assertEquals(
                "Celsius Rewards" to "Celsius Network",
                route(all.at("2022-07-15T12:30:00Z")),
            )
            assertEquals(
                "Celsius Network" to "Celsius Withdrawals",
                route(all.at("2022-04-28T23:56:00Z")),
            )
        }

    @Test
    fun `a reward keeps every decimal place of its coin`() =
        runDbTest {
            apply(listOf(stage(listOf(reward))))

            val amount = transfers().single().amount
            assertEquals("ETH", amount.asset.code)
            assertEquals("0.000001206275885505", amount.toDisplayValue().toString())
        }

    @Test
    fun `a withdrawal Celsius never confirmed stays on record out of the balance`() =
        runDbTest {
            apply(listOf(stage(listOf(deposit, unconfirmed, retried))))

            assertEquals(3, transfers().size)
            assertEquals("1", celsiusBalance())
        }

    @Test
    fun `re-importing changes nothing`() =
        runDbTest {
            apply(listOf(stage(listOf(deposit, reward, promo, unconfirmed, retried))))
            val balance = celsiusBalance()
            val routes = transfers().map { route(it) }.groupingBy { it }.eachCount()

            val reimports = repositories.reimports(maintenance)
            reimports.reimportAll(
                reimports.planAll(repositories.csvImportRepository.getAllImports().first()),
                accountId = null,
                currencyId = null,
                onProgress = {},
            )

            assertEquals(balance, celsiusBalance())
            assertEquals(routes, transfers().map { route(it) }.groupingBy { it }.eachCount())
        }
}
