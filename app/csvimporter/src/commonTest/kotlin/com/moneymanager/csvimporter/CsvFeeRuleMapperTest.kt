package com.moneymanager.csvimporter

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.CurrencyScaleFactors
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvColumnId
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csvstrategy.AccountRule
import com.moneymanager.domain.model.csvstrategy.AccountRulesMapping
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.ConditionOp
import com.moneymanager.domain.model.rules.Extraction
import com.moneymanager.domain.model.rules.FeeRule
import com.moneymanager.domain.model.rules.ValueExpr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** The CSV side of the shared [FeeRule]: conditions, currency, description, gross amounts and charged-on asset. */
class CsvFeeRuleMapperTest {
    private val now = Clock.System.now()

    private val usd =
        Currency(id = CurrencyId(1), code = "USD", name = "US Dollar", scaleFactor = CurrencyScaleFactors.DEFAULT_SCALE_FACTOR)
    private val eur = Currency(id = CurrencyId(2), code = "EUR", name = "Euro", scaleFactor = CurrencyScaleFactors.DEFAULT_SCALE_FACTOR)
    private val wallet = Account(id = AccountId(1), name = "Wallet", openingDate = now)
    private val shop = Account(id = AccountId(2), name = "Shop", openingDate = now)

    private val columnNames = listOf("Amount", "Asset", "Date", "Payee", "Fee", "FeeAsset", "Note", "Pair", "Kind")
    private val columns = columnNames.mapIndexed { index, name -> CsvColumn(CsvColumnId(Uuid.random()), index, name) }

    private fun strategy(fee: FeeRule) =
        CsvImportStrategy(
            id = CsvImportStrategyId(Uuid.random()),
            name = "Fees",
            config =
                CsvStrategyConfig(
                    identificationColumns = columnNames.toSet(),
                    fieldMappings =
                        mapOf(
                            TransferField.SOURCE_ACCOUNT to
                                AccountRulesMapping(
                                    fieldType = TransferField.SOURCE_ACCOUNT,
                                    rules = listOf(AccountRule(value = ValueExpr(listOf("Payee")), pattern = "^", name = "Wallet")),
                                ),
                            TransferField.TARGET_ACCOUNT to
                                AccountRulesMapping(
                                    fieldType = TransferField.TARGET_ACCOUNT,
                                    rules = listOf(AccountRule(value = ValueExpr(listOf("Payee")))),
                                ),
                            TransferField.AMOUNT to
                                AmountParsingMapping(
                                    fieldType = TransferField.AMOUNT,
                                    mode = AmountMode.SINGLE_COLUMN,
                                    amountColumnName = "Amount",
                                    fee = fee,
                                ),
                            TransferField.CURRENCY to
                                CurrencyLookupMapping(fieldType = TransferField.CURRENCY, value = ValueExpr(listOf("Asset"))),
                            TransferField.TIMESTAMP to
                                DateTimeParsingMapping(TransferField.TIMESTAMP, dateColumnName = "Date", dateFormat = "yyyy-MM-dd"),
                            TransferField.DESCRIPTION to
                                DirectColumnMapping(fieldType = TransferField.DESCRIPTION, value = ValueExpr(listOf("Payee"))),
                        ),
                ),
            createdAt = now,
            updatedAt = now,
        )

    private fun map(
        fee: FeeRule,
        amount: String = "10",
        asset: String = "USD",
        feeValue: String = "1.5",
        feeAsset: String = "",
        note: String = "",
        pair: String = "",
        kind: String = "",
    ): MappingResult.Success {
        val mapper =
            CsvTransferMapper(
                strategy = strategy(fee),
                columns = columns,
                existingAccounts = mapOf(wallet.name to wallet, shop.name to shop),
                existingCurrencies = mapOf(usd.id to usd, eur.id to eur),
                existingCurrenciesByCode = mapOf(usd.code to usd, eur.code to eur),
            )
        val row = CsvRow(rowIndex = 1, values = listOf(amount, asset, "2024-01-01", "Shop", feeValue, feeAsset, note, pair, kind))
        return assertIs<MappingResult.Success>(mapper.mapRow(row))
    }

    private fun usd(display: String) = Money.fromDisplayValue(BigDecimal(display), usd)

    private val fee = FeeRule(amount = ValueExpr(listOf("Fee")))

    @Test
    fun aFeeIsItsOwnMovementOnTopOfTheAmount() {
        val r = map(fee)
        assertEquals(usd("10"), r.transfer.amount)
        assertEquals(usd("1.5"), r.feeAmount)
        assertNull(r.feeDescription)
    }

    @Test
    fun aGrossAmountHasItsFeeCarvedOut() {
        val r = map(fee.copy(includedInAmount = true))
        assertEquals(usd("8.5"), r.transfer.amount)
        assertEquals(usd("1.5"), r.feeAmount)
    }

    @Test
    fun aFeeInAnotherAssetIsNotCarvedOutOfTheAmount() {
        val r = map(fee.copy(includedInAmount = true, currency = ValueExpr(listOf("FeeAsset"))), feeAsset = "EUR")
        assertEquals(usd("10"), r.transfer.amount)
        assertEquals(Money.fromDisplayValue(BigDecimal("1.5"), eur), r.feeAmount)
    }

    @Test
    fun theDescriptionComesFromItsColumn() {
        assertEquals("ATM fee", map(fee.copy(description = ValueExpr(listOf("Note"))), note = "ATM fee").feeDescription)
    }

    @Test
    fun conditionsGateTheFee() {
        val gated = fee.copy(conditions = listOf(Condition("Kind", ConditionOp.EQUALS, value = "OUT")))
        assertEquals(usd("1.5"), map(gated, kind = "OUT").feeAmount)
        assertNull(map(gated, kind = "IN").feeAmount)
    }

    @Test
    fun aFeeChargedOnOneAssetIsBookedOnlyOnTheRowInThatAsset() {
        val quote = fee.copy(chargedOnAsset = ValueExpr(listOf("Pair"), Extraction("^.*-(.*)$", "$1")))
        assertEquals(usd("1.5"), map(quote, pair = "BTC-USD").feeAmount)
        assertNull(map(quote, pair = "BTC-EUR").feeAmount)
        assertNull(map(quote).feeAmount, "a row naming no pair carries no fee")
    }
}
