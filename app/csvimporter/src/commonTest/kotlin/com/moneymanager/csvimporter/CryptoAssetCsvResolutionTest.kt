@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.csvimporter

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.model.CryptoId
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvColumnId
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.FieldMappingId
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedTimezoneMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * A CSV strategy whose CURRENCY lookup column carries a crypto ticker (e.g. "CRO") resolves to a
 * crypto [CryptoAsset] when one is supplied, producing a crypto-denominated transfer (real crypto
 * balance) rather than failing with "Currency not found". This is the foundation the crypto.com
 * importer builds on.
 */
class CryptoAssetCsvResolutionTest {
    private val now = Clock.System.now()
    private val external = Account(id = AccountId(1), name = "External", openingDate = now)
    private val wallet = Account(id = AccountId(2), name = "Crypto.com CRO", openingDate = now)
    private val cro = CryptoAsset(id = CryptoId(50), code = "CRO", name = "Cronos")

    private val columns =
        listOf("Date", "Amount", "Currency")
            .mapIndexed { index, name -> CsvColumn(CsvColumnId(Uuid.random()), index, name) }

    private fun id() = FieldMappingId(Uuid.random())

    private val strategy =
        CsvImportStrategy(
            id = CsvImportStrategyId(Uuid.random()),
            name = "Crypto lookup",
            identificationColumns = setOf("Date", "Amount", "Currency"),
            fieldMappings =
                mapOf(
                    TransferField.TIMESTAMP to
                        DateTimeParsingMapping(id(), TransferField.TIMESTAMP, dateColumnName = "Date", dateFormat = "yyyy-MM-dd"),
                    TransferField.SOURCE_ACCOUNT to HardCodedAccountMapping(id(), TransferField.SOURCE_ACCOUNT, external.id),
                    TransferField.TARGET_ACCOUNT to HardCodedAccountMapping(id(), TransferField.TARGET_ACCOUNT, wallet.id),
                    TransferField.DESCRIPTION to DirectColumnMapping(id(), TransferField.DESCRIPTION, columnName = "Currency"),
                    TransferField.AMOUNT to
                        AmountParsingMapping(id(), TransferField.AMOUNT, mode = AmountMode.SINGLE_COLUMN, amountColumnName = "Amount"),
                    TransferField.CURRENCY to CurrencyLookupMapping(id(), TransferField.CURRENCY, columnName = "Currency"),
                    TransferField.TIMEZONE to HardCodedTimezoneMapping(id(), TransferField.TIMEZONE, "UTC"),
                ),
            createdAt = now,
            updatedAt = now,
        )

    @Test
    fun cryptoCurrencyColumnResolvesToCryptoAsset() {
        val mapper =
            buildCsvMapper(
                strategy = strategy,
                columns = columns,
                accounts = listOf(external, wallet),
                currencies = emptyList(),
                accountMappings = emptyList(),
                sourceAccountOverride = null,
                cryptoAssets = listOf(cro),
            )

        val result = mapper.mapRow(CsvRow(rowIndex = 1, values = listOf("2021-07-04", "48.78055303", "CRO")))
        val success = assertIs<MappingResult.Success>(result, "crypto row should map: ${(result as? MappingResult.Error)?.errorMessage}")

        assertTrue(success.transfer.amount.asset is CryptoAsset, "denominated in a crypto asset")
        assertEquals("CRO", success.transfer.amount.asset.code)
        assertEquals(Money.fromDisplayValue("48.78055303", cro), success.transfer.amount)
    }

    @Test
    fun eighteenDecimalDustAmountParsesExactly() {
        // Regression: crypto.com "Convert Dust" rows carry 18 fractional digits. With the fixed
        // 18-decimal crypto scale they must map instead of failing with "Rounding necessary".
        val mapper =
            buildCsvMapper(
                strategy = strategy,
                columns = columns,
                accounts = listOf(external, wallet),
                currencies = emptyList(),
                accountMappings = emptyList(),
                sourceAccountOverride = null,
                cryptoAssets = listOf(cro),
            )

        val result = mapper.mapRow(CsvRow(rowIndex = 1, values = listOf("2023-11-01", "0.011548987600813619", "CRO")))
        val success = assertIs<MappingResult.Success>(result, "dust row should map: ${(result as? MappingResult.Error)?.errorMessage}")

        assertEquals(Money.fromDisplayValue("0.011548987600813619", cro), success.transfer.amount)
    }
}
