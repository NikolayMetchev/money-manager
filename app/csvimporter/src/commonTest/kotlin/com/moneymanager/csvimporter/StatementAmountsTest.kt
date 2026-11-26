package com.moneymanager.csvimporter

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.RuleEvaluator
import com.moneymanager.domain.model.rules.ValueExpr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StatementAmountsTest {
    private val usd = Currency(id = CurrencyId(1), code = "USD", name = "US Dollar", scaleFactor = 100)
    private val eur = Currency(id = CurrencyId(2), code = "EUR", name = "Euro", scaleFactor = 100)
    private val amounts =
        StatementAmounts(
            config = CsvStrategyConfig(identificationColumns = emptySet(), fieldMappings = emptyMap()),
            rules = RuleEvaluator(),
            existingCurrencies = mapOf(usd.id to usd, eur.id to eur),
            existingCurrenciesByCode = mapOf(usd.code to usd, eur.code to eur),
            existingCryptoByCode = emptyMap(),
            assetAliases = emptyMap(),
        )
    private val splitAmount =
        AmountParsingMapping(
            fieldType = TransferField.AMOUNT,
            mode = AmountMode.CREDIT_DEBIT_COLUMNS,
            creditColumnName = "Credit",
            debitColumnName = "Debit",
        )

    @Test
    fun splitAmountSubtractsDebitFromCreditAndTreatsBlankCellsAsZero() {
        val columns = mapOf("Credit" to 0, "Debit" to 1)
        assertEquals(BigDecimal("20.50"), amounts.parseAmount(splitAmount, RowValues(listOf("20.50", ""), columns, emptySet())))
        assertEquals(BigDecimal("-4.25"), amounts.parseAmount(splitAmount, RowValues(listOf("", "4.25"), columns, emptySet())))
        assertEquals(BigDecimal("16.25"), amounts.parseAmount(splitAmount, RowValues(listOf("20.50", "4.25"), columns, emptySet())))
    }

    @Test
    fun balanceUsesItsOwnCurrencyAndPreservesItsSign() {
        val balance = RuleAction.Balance(ValueExpr(listOf("Balance")), currency = ValueExpr(listOf("Currency")))
        val columns = mapOf("Balance" to 0, "Currency" to 1)
        val explicit = RowValues(listOf("-123.45", "EUR"), columns, emptySet())
        val implicit = RowValues(listOf("-123.45", ""), columns, emptySet())
        val unknown = RowValues(listOf("-123.45", "ZZZ"), columns, emptySet())

        assertEquals(Money.fromDisplayValue(BigDecimal("-123.45"), eur), amounts.parseBalance(balance, explicit, usd))
        assertEquals(Money.fromDisplayValue(BigDecimal("-123.45"), usd), amounts.parseBalance(balance, implicit, usd))
        val error = assertFailsWith<IllegalStateException> { amounts.parseBalance(balance, unknown, usd) }
        assertEquals("Balance currency not found: ZZZ", error.message)
    }
}
