package com.moneymanager.csvimporter

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.builtin.BuiltInCsvStrategies
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.CurrencyScaleFactors
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvColumnId
import com.moneymanager.domain.model.csv.CsvRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

class BarclaycardCsvMapperTest {
    private val now = Instant.parse("2026-02-01T00:00:00Z")
    private val gbp =
        Currency(id = CurrencyId(1), code = "GBP", name = "British Pound", scaleFactor = CurrencyScaleFactors.DEFAULT_SCALE_FACTOR)
    private val barclaycard = Account(id = AccountId(1), name = "Barclaycard", openingDate = now)
    private val maskedCardNumber = "************1234"
    private val columns =
        listOf("Date", "Account/Card No", "Amount", "Subcategory", "Memo")
            .mapIndexed { index, name -> CsvColumn(CsvColumnId(Uuid.random()), index, name) }

    private fun map(
        amount: String,
        type: String,
        memo: String,
    ): MappingResult.Success {
        val row = CsvRow(1L, listOf("24/02/2026", maskedCardNumber, amount, type, memo))
        val strategy =
            BuiltInCsvStrategies
                .builtInCsvStrategies(now, gbp.id)
                .selectForCsv("Transactions - 2026-02-25T12_00_00.000Z.csv", columns, listOf(row))
        assertEquals("Barclaycard CSV", strategy?.name)
        val mapper =
            CsvTransferMapper(
                strategy = requireNotNull(strategy),
                columns = columns,
                existingAccounts = mapOf(barclaycard.name to barclaycard),
                existingCurrencies = mapOf(gbp.id to gbp),
                existingCurrenciesByCode = mapOf(gbp.code to gbp),
            )
        return assertIs<MappingResult.Success>(mapper.mapRow(row))
    }

    @Test
    fun positivePurchaseDebitsTheCardAndKeepsTheOriginalMemo() {
        val memo = "BIKE SHOP\tBike Shop, London"
        val result = map("1,234.56", "Purchase", memo)
        assertEquals(barclaycard.id, result.transfer.sourceAccountId)
        assertEquals("BIKE SHOP", result.transfer.description)
        assertEquals("BIKE SHOP", result.newAccounts.single().name)
        assertEquals(Instant.parse("2026-02-24T12:00:00Z"), result.transfer.timestamp)
        assertEquals(Money.fromDisplayValue(BigDecimal("1234.56"), gbp), result.transfer.amount)
        assertEquals(maskedCardNumber, result.attributes.toMap()["barclaycard-card-number"])
        assertEquals("Purchase", result.attributes.toMap()["barclaycard-subcategory"])
        assertEquals(memo, result.attributes.toMap()["barclaycard-memo"])
    }

    @Test
    fun negativePaymentCreditsTheCardFromAnUnidentifiedPayer() {
        val result = map("-1,234.56", "Payment received", "PAYMENT BY DIRECT DEBIT\tPayment By Direct De")
        assertEquals(barclaycard.id, result.transfer.targetAccountId)
        assertEquals(result.transfer.sourceAccountId, result.unidentifiedCounterpartyAccountId)
        assertEquals(Money.fromDisplayValue(BigDecimal("1234.56"), gbp), result.transfer.amount)
    }

    @Test
    fun negativePurchaseCreditsTheCardFromTheNamedMerchant() {
        val result = map("-9.95", "Purchase", "BOOKSHOP             Bookshop, Oxford")
        assertEquals(barclaycard.id, result.transfer.targetAccountId)
        assertEquals("BOOKSHOP", result.newAccounts.single().name)
        assertNull(result.unidentifiedCounterpartyAccountId)
    }
}
