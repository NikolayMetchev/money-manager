@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.csv

import com.moneymanager.csvimporter.applyStagedCsv
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.csv.CsvImport
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
 * End-to-end for the crypto-aware CSV import: a strategy whose currency column carries a crypto ticker
 * (e.g. "CRO") triggers on-demand creation of the crypto asset during import and produces a real
 * crypto-denominated balance. Re-importing the same file is idempotent — no duplicate transfer, no
 * duplicate asset, balance unchanged.
 */
class CryptoCsvReimportE2ETest : DbTest() {
    override val seedAllCurrencies: Boolean = false

    private val now = Clock.System.now()
    private val headers = listOf("Date", "Amount", "Currency", "Description")

    // Stubs materialized-view maintenance; the test refreshes explicitly before reading balances.
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
        income: AccountId,
        wallet: AccountId,
    ) = CsvImportStrategy(
        id = CsvImportStrategyId(Uuid.random()),
        name = "Crypto rewards",
        identificationColumns = headers.toSet(),
        fieldMappings =
            mapOf(
                TransferField.TIMESTAMP to
                    DateTimeParsingMapping(mid(), TransferField.TIMESTAMP, dateColumnName = "Date", dateFormat = "yyyy-MM-dd"),
                TransferField.SOURCE_ACCOUNT to HardCodedAccountMapping(mid(), TransferField.SOURCE_ACCOUNT, income),
                TransferField.TARGET_ACCOUNT to HardCodedAccountMapping(mid(), TransferField.TARGET_ACCOUNT, wallet),
                TransferField.DESCRIPTION to DirectColumnMapping(mid(), TransferField.DESCRIPTION, columnName = "Description"),
                TransferField.AMOUNT to
                    AmountParsingMapping(mid(), TransferField.AMOUNT, mode = AmountMode.SINGLE_COLUMN, amountColumnName = "Amount"),
                TransferField.CURRENCY to CurrencyLookupMapping(mid(), TransferField.CURRENCY, columnName = "Currency"),
                TransferField.TIMEZONE to HardCodedTimezoneMapping(mid(), TransferField.TIMEZONE, "UTC"),
            ),
        createdAt = now,
        updatedAt = now,
    )

    private suspend fun stage(fileName: String): CsvImport {
        val id =
            repositories.csvImportRepository.createImport(
                fileName = fileName,
                headers = headers,
                rows = listOf(listOf("2021-07-04", "48.78055303", "CRO", "Card Cashback")),
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
    fun cryptoCsv_createsAssetOnImport_andReimportIsIdempotent() =
        runTest {
            val income = repositories.accountRepository.createAccount(Account(id = AccountId(0), name = "Rewards", openingDate = now))
            val wallet = repositories.accountRepository.createAccount(Account(id = AccountId(0), name = "CRO Wallet", openingDate = now))
            val strategy = strategy(income, wallet)

            // First import: no CRO asset exists yet — it must be created on demand.
            assertEquals(null, repositories.cryptoRepository.getCryptoAssetByCode("CRO").first())
            val first = apply(stage("cashback-1.csv"), strategy)
            assertNotNull(first)
            assertEquals(1, first.successCount)

            val cro = repositories.cryptoRepository.getCryptoAssetByCode("CRO").first()
            assertNotNull(cro, "CRO crypto asset created on demand")
            assertEquals("Cronos", cro.name)

            repositories.maintenanceService.refreshMaterializedViews()
            val walletBalance =
                repositories.transactionRepository
                    .getAccountBalances()
                    .first()
                    .first { it.accountId == wallet }
                    .balance
            assertEquals("CRO", walletBalance.currency.code)
            assertEquals("48.78055303", walletBalance.toDisplayValue().toString())

            // Re-import the same file (fresh staged copy): deduped, no new transfer, no new asset.
            val second = apply(stage("cashback-2.csv"), strategy)
            // Either no result (nothing new) or a result with zero successes and one duplicate.
            assertEquals(0, second?.successCount ?: 0, "reimport must not create a new transfer")

            assertEquals(
                1,
                repositories.cryptoRepository
                    .getAllCryptoAssets()
                    .first()
                    .count { it.code == "CRO" },
                "no duplicate CRO asset",
            )

            repositories.maintenanceService.refreshMaterializedViews()
            val walletBalanceAfter =
                repositories.transactionRepository
                    .getAccountBalances()
                    .first()
                    .first { it.accountId == wallet }
                    .balance
            assertEquals("48.78055303", walletBalanceAfter.toDisplayValue().toString(), "balance unchanged after reimport")
        }
}
