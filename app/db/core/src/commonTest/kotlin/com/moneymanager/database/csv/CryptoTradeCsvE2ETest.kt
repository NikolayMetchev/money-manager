@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.csv

import com.moneymanager.csvimporter.applyStagedCsv
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.FieldMappingId
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedTimezoneMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.createAccount
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.uuid.Uuid

/**
 * A CSV strategy mapping TO_CURRENCY/TO_AMOUNT emits a cross-asset **trade** for conversion rows: a
 * "buy 0.005 BTC for £100" row becomes a trade (GBP leaves the cash account, BTC enters the wallet),
 * with the BTC asset created on demand — so both accounts show correct, full-precision balances.
 */
class CryptoTradeCsvE2ETest : DbTest() {
    override val seedAllCurrencies: Boolean = false

    private val now = Clock.System.now()
    private val headers = listOf("Date", "Amount", "Currency", "To Amount", "To Currency", "Description")

    private val maintenance =
        object : Maintenance {
            override suspend fun reindex(): Duration = Duration.ZERO

            override suspend fun vacuum(): Duration = Duration.ZERO

            override suspend fun analyze(): Duration = Duration.ZERO

            override suspend fun refreshMaterializedViews(): Duration = Duration.ZERO

            override suspend fun fullRefreshMaterializedViews(): Duration = Duration.ZERO
        }

    private fun mid() = FieldMappingId(Uuid.random())

    private fun strategy(
        cash: AccountId,
        wallet: AccountId,
    ) = CsvImportStrategy(
        id = CsvImportStrategyId(Uuid.random()),
        name = "Crypto buys",
        identificationColumns = headers.toSet(),
        fieldMappings =
            mapOf(
                TransferField.TIMESTAMP to
                    DateTimeParsingMapping(mid(), TransferField.TIMESTAMP, dateColumnName = "Date", dateFormat = "yyyy-MM-dd"),
                TransferField.SOURCE_ACCOUNT to HardCodedAccountMapping(mid(), TransferField.SOURCE_ACCOUNT, cash),
                TransferField.TARGET_ACCOUNT to HardCodedAccountMapping(mid(), TransferField.TARGET_ACCOUNT, wallet),
                TransferField.DESCRIPTION to DirectColumnMapping(mid(), TransferField.DESCRIPTION, columnName = "Description"),
                TransferField.AMOUNT to
                    AmountParsingMapping(mid(), TransferField.AMOUNT, mode = AmountMode.SINGLE_COLUMN, amountColumnName = "Amount"),
                TransferField.CURRENCY to CurrencyLookupMapping(mid(), TransferField.CURRENCY, columnName = "Currency"),
                TransferField.TO_AMOUNT to
                    AmountParsingMapping(mid(), TransferField.TO_AMOUNT, mode = AmountMode.SINGLE_COLUMN, amountColumnName = "To Amount"),
                TransferField.TO_CURRENCY to CurrencyLookupMapping(mid(), TransferField.TO_CURRENCY, columnName = "To Currency"),
                TransferField.TIMEZONE to HardCodedTimezoneMapping(mid(), TransferField.TIMEZONE, "UTC"),
            ),
        createdAt = now,
        updatedAt = now,
    )

    private suspend fun stage(fileName: String = "buys.csv"): CsvImport {
        val id =
            repositories.csvImportRepository.createImport(
                fileName = fileName,
                headers = headers,
                rows = listOf(listOf("2021-07-04", "100", "GBP", "0.005", "BTC", "Buy BTC")),
                fileChecksum = "checksum-$fileName",
                fileLastModified = now,
            )
        return repositories.csvImportRepository.getImport(id).first()!!
    }

    private suspend fun apply(
        csvImport: CsvImport,
        strategy: CsvImportStrategy,
    ) = applyStagedCsv(
        csvImport = csvImport,
        strategy = strategy,
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

    @Test
    fun conversionRow_importsAsTrade_withRealCryptoBalance() =
        runTest {
            val cash = repositories.accountRepository.createAccount(Account(id = AccountId(0), name = "Cash", openingDate = now))
            val wallet = repositories.accountRepository.createAccount(Account(id = AccountId(0), name = "BTC Wallet", openingDate = now))

            applyStagedCsv(
                csvImport = stage(),
                strategy = strategy(cash, wallet),
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

            // BTC asset created on demand (from the To Currency column).
            val btc = repositories.cryptoRepository.getCryptoAssetByCode("BTC").first()
            assertNotNull(btc, "BTC crypto asset created on demand")

            // A trade was created (not a transfer): cash → wallet, GBP → BTC.
            val trades = repositories.tradeRepository.getTradesByAccount(wallet).first()
            assertEquals(1, trades.size)
            val trade = trades.single()
            assertEquals(cash, trade.fromAccountId)
            assertEquals("GBP", trade.from.currency.code)
            assertEquals("100", trade.from.toDisplayValue().toString())
            assertEquals(wallet, trade.toAccountId)
            assertEquals("BTC", trade.to.currency.code)
            assertEquals("0.005", trade.to.toDisplayValue().toString())

            // Balances reflect both legs.
            repositories.maintenanceService.refreshMaterializedViews()
            val balances = repositories.transactionRepository.getAccountBalances().first()
            assertEquals(
                "0.005",
                balances
                    .first { it.accountId == wallet }
                    .balance
                    .toDisplayValue()
                    .toString(),
            )
            assertEquals(
                "-100",
                balances
                    .first { it.accountId == cash }
                    .balance
                    .toDisplayValue()
                    .toString(),
            )
        }

    @Test
    fun conversionRow_getsImportedStatus_notLeftAsError() =
        runTest {
            val cash = repositories.accountRepository.createAccount(Account(id = AccountId(0), name = "Cash", openingDate = now))
            val wallet = repositories.accountRepository.createAccount(Account(id = AccountId(0), name = "BTC Wallet", openingDate = now))
            val csvImport = stage()

            apply(csvImport, strategy(cash, wallet))

            // The conversion row is imported as a trade (reported via createdTradeIds, not rowOutcomes);
            // its CSV row status must become IMPORTED with its error cleared — not stay ERROR, which would
            // reprocess it on the next import.
            val row =
                repositories.csvImportRepository
                    .getImportRows(csvImport.id, limit = 10, offset = 0)
                    .single { it.values.contains("Buy BTC") }
            assertEquals(ImportStatus.IMPORTED, row.importStatus, "conversion (trade) row must be marked IMPORTED")
        }

    @Test
    fun conversionTrade_reimportIsIdempotent() =
        runTest {
            val cash = repositories.accountRepository.createAccount(Account(id = AccountId(0), name = "Cash", openingDate = now))
            val wallet = repositories.accountRepository.createAccount(Account(id = AccountId(0), name = "BTC Wallet", openingDate = now))
            val strategy = strategy(cash, wallet)

            apply(stage("buys-1.csv"), strategy)
            // Re-import the same conversion (fresh staged copy): must not create a second trade.
            apply(stage("buys-2.csv"), strategy)

            assertEquals(
                1,
                repositories.tradeRepository
                    .getTradesByAccount(wallet)
                    .first()
                    .size,
                "no duplicate trade on re-import",
            )

            repositories.maintenanceService.refreshMaterializedViews()
            val walletBalance =
                repositories.transactionRepository
                    .getAccountBalances()
                    .first()
                    .first { it.accountId == wallet }
                    .balance
            assertEquals("0.005", walletBalance.toDisplayValue().toString(), "balance unchanged after re-import")
        }
}
