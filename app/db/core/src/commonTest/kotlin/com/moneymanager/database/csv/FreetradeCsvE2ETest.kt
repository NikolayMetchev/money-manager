package com.moneymanager.database.csv

import com.moneymanager.csvimporter.applyStagedCsv
import com.moneymanager.csvimporter.bulkApplyCsv
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedTimezoneMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.rules.ValueExpr
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.createAccount
import com.moneymanager.test.database.reimports
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.uuid.Uuid

/**
 * End-to-end cover for the built-in Freetrade strategies: rows shaped like the activity export go through the real
 * engine. The file name is the only thing telling an ISA export from a GIA one.
 */
class FreetradeCsvE2ETest : DbTest() {
    override val installBuiltInStrategies: Boolean = true

    private val now = Clock.System.now()

    private val headers =
        (
            "Title,Type,Timestamp,Account Currency,Total Amount in Account Currency,Buy / Sell,Ticker,ISIN," +
                "Price per Share in Account Currency,Stamp Duty,Quantity,Venue,Order ID,Order Type,Instrument Currency," +
                "Total Amount in Instrument Currency,Price per Share,FX Rate,Base FX Rate,FX Fee (BPS),FX Fee Amount," +
                "Dividend Ex Date,Dividend Pay Date,Dividend Eligible Quantity,Dividend Amount Per Share," +
                "Dividend Gross Distribution Amount,Dividend Net Distribution Amount,Dividend Withheld Tax Percentage," +
                "Dividend Withheld Tax Amount,Stock Split Ex Date,Stock Split Pay Date,Stock Split New ISIN," +
                "Stock Split Rate of Share Outturn From,Stock Split Rate of Share Outturn To," +
                "Stock Split Maintain Holding of Initial ISIN,Stock Split New Share Quantity," +
                "Stock Split Rate of Cash Outturn Amount,Stock Split Rate of Cash Outturn Currency," +
                "Stock Split Cash Outturn Received Amount,Stock Split Has Fractional Payout," +
                "Stock Split Rate of Fractional Payout Amount,Stock Split Rate of Fractional Payout Currency," +
                "Stock Split Fractional Payout Cash Received Amount,Stock Split Fractional Payout Cash Received Currency"
        ).split(",")

    private val maintenance =
        object : Maintenance {
            override suspend fun reindex(): Duration = Duration.ZERO

            override suspend fun vacuum(): Duration = Duration.ZERO

            override suspend fun analyze(): Duration = Duration.ZERO

            override suspend fun refreshMaterializedViews(): Duration = Duration.ZERO

            override suspend fun fullRefreshMaterializedViews(): Duration = Duration.ZERO
        }

    private fun row(vararg cells: Pair<String, String>): List<String> {
        val byColumn = cells.toMap()
        return headers.map { byColumn[it].orEmpty() }
    }

    private fun cash(
        title: String,
        type: String,
        time: String,
        total: String,
    ) = row(
        "Title" to title,
        "Type" to type,
        "Timestamp" to time,
        "Account Currency" to "GBP",
        "Total Amount in Account Currency" to total,
    )

    private fun order(
        side: String,
        time: String,
        total: String,
        quantity: String,
        type: String = "ORDER",
        stampDuty: String = "0.00",
    ) = row(
        "Title" to "Example Fund",
        "Type" to type,
        "Timestamp" to time,
        "Account Currency" to "GBP",
        "Total Amount in Account Currency" to total,
        "Buy / Sell" to side,
        "Ticker" to "EXFD",
        "ISIN" to "GB0000000001",
        "Stamp Duty" to stampDuty,
        "Quantity" to quantity,
        "Venue" to "London Stock Exchange",
        "Order ID" to "ORDER$time",
    )

    private val statement = row("Title" to "October Statement", "Type" to "MONTHLY_STATEMENT", "Timestamp" to "2026-11-01T00:00:00.000Z")
    private val topUp = cash("Top up", "TOP_UP", "2026-03-01T10:28:23.178Z", "1000.00")
    private val interest = cash("Interest", "INTEREST_FROM_CASH", "2026-02-16T00:00:00.000Z", "0.02")
    private val buy = order("BUY", "2026-03-02T08:49:09.042Z", "998.64", "202.00000000")

    private suspend fun stage(
        rows: List<List<String>>,
        fileName: String = "freetrade_ISA_all_activity_max_07-11-2026.csv",
    ): CsvImport {
        val id =
            repositories.csvImportRepository.createImport(
                fileName = fileName,
                headers = headers,
                rows = rows,
                fileChecksum = "checksum-$fileName-${rows.hashCode()}",
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

    private suspend fun accountId(name: String): AccountId = accounts().single { it.name == name }.id

    private suspend fun transfers(): List<Transfer> =
        accounts()
            .flatMap { repositories.transactionRepository.getTransactionsByAccount(it.id).first() }
            .distinctBy { it.id }

    /** Every balance of [account], by asset code, as text. */
    private suspend fun balances(account: String = "Freetrade ISA"): Map<String, String> {
        repositories.maintenanceService.refreshMaterializedViews()
        val id = accountId(account)
        return repositories.transactionRepository
            .getAccountBalances()
            .first()
            .filter { it.accountId == id }
            .associate {
                it.balance.asset.code to
                    it.balance
                        .toDisplayValue()
                        .toString()
            }
    }

    @Test
    fun `an ISA export books cash and shares on the ISA account`() =
        runDbTest {
            val result = apply(listOf(stage(listOf(statement, buy, topUp, interest))))
            assertEquals(1, result.filesImported)

            assertEquals(mapOf("GBP" to "1.38", "EXFD" to "202"), balances())
            assertEquals(mapOf("GBP" to "-1000"), balances("Freetrade Top Ups"))
            assertEquals(mapOf("GBP" to "-0.02"), balances("Freetrade Interest"))
        }

    @Test
    fun `a purchase is a trade within the account in UTC`() =
        runDbTest {
            apply(listOf(stage(listOf(buy))))

            val isa = accountId("Freetrade ISA")
            val trade =
                repositories.tradeRepository
                    .getTradesByAccount(isa)
                    .first()
                    .single()
            assertEquals(isa, trade.fromAccountId)
            assertEquals(isa, trade.toAccountId)
            assertEquals("GBP", trade.from.asset.code)
            assertEquals("EXFD", trade.to.asset.code)
            assertEquals("2026-03-02T08:49:09.042Z", trade.timestamp.toString())
            assertEquals("Example Fund", trade.description)
        }

    @Test
    fun `a sale gives up the shares for the total`() =
        runDbTest {
            val sell = order("SELL", "2026-04-02T10:12:44.120Z", "200.00", "40.00000000")
            apply(listOf(stage(listOf(topUp, buy, sell))))

            assertEquals(mapOf("GBP" to "201.36", "EXFD" to "162"), balances())
        }

    @Test
    fun `stamp duty in the total is not charged again`() =
        runDbTest {
            apply(listOf(stage(listOf(topUp, order("BUY", "2026-03-02T08:49:09.042Z", "500.00", "10.00000000", stampDuty = "2.50")))))

            assertEquals(mapOf("GBP" to "500", "EXFD" to "10"), balances())
        }

    @Test
    fun `a dividend and a withdrawal stay cash whatever ticker and quantity they name`() =
        runDbTest {
            val dividend =
                row(
                    "Title" to "Example Fund",
                    "Type" to "DIVIDEND",
                    "Timestamp" to "2026-03-28T18:45:00.000Z",
                    "Account Currency" to "GBP",
                    "Total Amount in Account Currency" to "3.00",
                    "Ticker" to "EXFD",
                    "Quantity" to "10.00000000",
                )
            val reit = cash("Example REIT", "PROPERTY", "2026-03-29T18:45:00.000Z", "2.00")
            val withdrawal = cash("Withdrawal", "WITHDRAWAL", "2026-04-18T18:09:12.259Z", "500.00")
            apply(listOf(stage(listOf(topUp, dividend, reit, withdrawal))))

            assertEquals(mapOf("GBP" to "505"), balances())
            assertEquals(mapOf("GBP" to "-5"), balances("Freetrade Dividends"))
            assertEquals(mapOf("GBP" to "500"), balances("Freetrade Withdrawals"))
            assertEquals(
                0,
                repositories.tradeRepository
                    .getTradesByAccount(accountId("Freetrade ISA"))
                    .first()
                    .size,
            )
        }

    @Test
    fun `a free share costs the account no cash`() =
        runDbTest {
            apply(listOf(stage(listOf(topUp, order("BUY", "2026-01-21T15:14:54.175Z", "4.95", "1.00000000", type = "FREESHARE_ORDER")))))

            assertEquals(mapOf("GBP" to "1000", "EXFD" to "1"), balances())
            assertEquals(mapOf("GBP" to "-4.95"), balances("Freetrade Free Shares"))
        }

    @Test
    fun `the file name picks the account`() =
        runDbTest {
            val result = apply(listOf(stage(listOf(topUp), fileName = "freetrade_GIA_all_activity_max_07-11-2026.csv")))

            assertEquals(1, result.filesImported)
            assertEquals(mapOf("GBP" to "1000"), balances("Freetrade GIA"))
            assertNull(accounts().firstOrNull { it.name == "Freetrade ISA" })
        }

    @Test
    fun `a file naming neither account type imports nothing rather than guessing`() =
        runDbTest {
            val result = apply(listOf(stage(listOf(topUp), fileName = "activity.csv")))

            assertEquals(0, result.filesImported)
            assertEquals(1, result.filesSkippedNoStrategy)
        }

    @Test
    fun `an export of nothing but statements imports without error`() =
        runDbTest {
            val result = apply(listOf(stage(listOf(statement), fileName = "freetrade_GIA_all_activity_max_07-11-2026.csv")))

            assertEquals(0, result.filesFailed)
            assertEquals(emptyList(), transfers())
        }

    @Test
    fun `equal top-ups days apart are both kept`() =
        runDbTest {
            apply(listOf(stage(listOf(topUp, cash("Top up", "TOP_UP", "2026-03-02T10:28:23.178Z", "1000.00")))))

            assertEquals(mapOf("GBP" to "2000"), balances())
        }

    @Test
    fun `a row of an unknown type is an error rather than dropped`() =
        runDbTest {
            val import = stage(listOf(topUp, cash("Something new", "SOMETHING_NEW", "2026-03-03T10:00:00.000Z", "5.00")))
            apply(listOf(import))

            val statuses =
                repositories.csvImportRepository
                    .getImportRows(import.id, limit = 10, offset = 0)
                    .associate { it.values.first() to it.importStatus }
            assertEquals(ImportStatus.IMPORTED, statuses["Top up"])
            assertEquals(ImportStatus.ERROR, statuses["Something new"])
        }

    @Test
    fun `a later export overlapping an earlier one adds only what is new`() =
        runDbTest {
            val earlier = stage(listOf(buy, topUp), fileName = "freetrade_ISA_all_activity_max_01-04-2026.csv")
            apply(listOf(earlier))
            val next = cash("Top up", "TOP_UP", "2026-05-01T10:00:00.000Z", "15.00")
            apply(listOf(stage(listOf(next, buy, topUp))))

            assertEquals(mapOf("GBP" to "16.36", "EXFD" to "202"), balances())
        }

    @Test
    fun `re-importing changes nothing`() =
        runDbTest {
            apply(listOf(stage(listOf(statement, buy, topUp, interest))))
            val before = balances()

            val reimports = repositories.reimports(maintenance)
            reimports.reimportAll(
                reimports.planAll(repositories.csvImportRepository.getAllImports().first()),
                accountId = null,
                currencyId = null,
                onProgress = {},
            )

            assertEquals(before, balances())
        }

    @Test
    fun `a top-up the bank statement already records counts once`() =
        runDbTest {
            val isa = repositories.accountRepository.createAccount(Account(id = AccountId(0), name = "Freetrade ISA", openingDate = now))
            val bank = repositories.accountRepository.createAccount(Account(id = AccountId(0), name = "Bank", openingDate = now))
            val bankHeaders = listOf("Date", "Amount", "Currency", "Description")
            val bankStrategy =
                CsvImportStrategy(
                    id = CsvImportStrategyId(Uuid.random()),
                    name = "Bank",
                    config =
                        CsvStrategyConfig(
                            identificationColumns = bankHeaders.toSet(),
                            fieldMappings =
                                mapOf(
                                    TransferField.TIMESTAMP to
                                        DateTimeParsingMapping(TransferField.TIMESTAMP, dateColumnName = "Date", dateFormat = "yyyy-MM-dd"),
                                    TransferField.SOURCE_ACCOUNT to HardCodedAccountMapping(TransferField.SOURCE_ACCOUNT, bank),
                                    TransferField.TARGET_ACCOUNT to HardCodedAccountMapping(TransferField.TARGET_ACCOUNT, isa),
                                    TransferField.DESCRIPTION to
                                        DirectColumnMapping(TransferField.DESCRIPTION, value = ValueExpr(listOf("Description"))),
                                    TransferField.AMOUNT to
                                        AmountParsingMapping(TransferField.AMOUNT, AmountMode.SINGLE_COLUMN, amountColumnName = "Amount"),
                                    TransferField.CURRENCY to
                                        CurrencyLookupMapping(TransferField.CURRENCY, value = ValueExpr(listOf("Currency"))),
                                    TransferField.TIMEZONE to HardCodedTimezoneMapping(TransferField.TIMEZONE, "UTC"),
                                ),
                        ),
                    createdAt = now,
                    updatedAt = now,
                )
            val bankImportId =
                repositories.csvImportRepository.createImport(
                    fileName = "bank.csv",
                    headers = bankHeaders,
                    rows = listOf(listOf("2026-03-01", "1000.00", "GBP", "FREETRADE")),
                    fileChecksum = "bank",
                    fileLastModified = now,
                )
            applyStagedCsv(
                csvImport = repositories.csvImportRepository.getImport(bankImportId).first()!!,
                strategy = bankStrategy,
                sourceAccountOverride = null,
                currencies = repositories.currencyRepository.getAllCurrencies().first(),
                accountMappingRepository = repositories.accountMappingRepository,
                accountRepository = repositories.accountRepository,
                csvImportRepository = repositories.csvImportRepository,
                maintenance = maintenance,
                importEngine = repositories.importEngine,
                refreshViews = false,
                cryptoRepository = repositories.cryptoRepository,
            )

            apply(listOf(stage(listOf(topUp))))

            assertEquals(mapOf("GBP" to "1000"), balances())
        }
}
