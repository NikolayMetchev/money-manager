package com.moneymanager.domain.model

import com.moneymanager.bigdecimal.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MoneyTest {
    private val usd =
        Currency(
            id = CurrencyId(1L),
            code = "USD",
            name = "US Dollar",
            scaleFactor = 100,
        )

    private val jpy =
        Currency(
            id = CurrencyId(2L),
            code = "JPY",
            name = "Japanese Yen",
            scaleFactor = 1,
        )

    private val bhd =
        Currency(
            id = CurrencyId(3L),
            code = "BHD",
            name = "Bahraini Dinar",
            scaleFactor = 1000,
        )

    @Test
    fun toDisplayValue_convertsCorrectlyForTwoDecimalPlaces() {
        val money = Money(12345, usd) // $123.45
        assertEquals("123.45", money.toDisplayValue().toString())
    }

    @Test
    fun toDisplayValue_convertsCorrectlyForZeroDecimalPlaces() {
        val money = Money(1000, jpy) // ¥1000
        assertEquals("1000", money.toDisplayValue().toString())
    }

    @Test
    fun toDisplayValue_convertsCorrectlyForThreeDecimalPlaces() {
        val money = Money(123456, bhd) // BD 123.456
        assertEquals("123.456", money.toDisplayValue().toString())
    }

    @Test
    fun fromDisplayValue_bigDecimal_convertsCorrectly() {
        val money = Money.fromDisplayValue(BigDecimal("123.45"), usd)
        assertEquals(12345L, money.amount)
        assertEquals(usd.id, money.currency.id)
    }

    @Test
    fun fromDisplayValue_double_convertsCorrectly() {
        val money = Money.fromDisplayValue(123.45, usd)
        assertEquals(12345L, money.amount)
    }

    @Test
    fun fromDisplayValue_string_convertsCorrectly() {
        val money = Money.fromDisplayValue("123.45", usd)
        assertEquals(12345L, money.amount)
    }

    @Test
    fun plus_addsTwoAmounts() {
        val a = Money(10000, usd) // $100.00
        val b = Money(5000, usd) // $50.00
        val result = a + b

        assertEquals(15000L, result.amount) // $150.00
        assertEquals(usd.id, result.currency.id)
    }

    @Test
    fun plus_throwsWhenCurrenciesDontMatch() {
        val a = Money(10000, usd)
        val b = Money(5000, jpy)

        assertFailsWith<IllegalArgumentException> {
            a + b
        }
    }

    @Test
    fun minus_subtractsTwoAmounts() {
        val a = Money(10000, usd) // $100.00
        val b = Money(3000, usd) // $30.00
        val result = a - b

        assertEquals(7000L, result.amount) // $70.00
        assertEquals(usd.id, result.currency.id)
    }

    @Test
    fun minus_throwsWhenCurrenciesDontMatch() {
        val a = Money(10000, usd)
        val b = Money(5000, jpy)

        assertFailsWith<IllegalArgumentException> {
            a - b
        }
    }

    @Test
    fun times_long_multipliesAmount() {
        val money = Money(1000, usd) // $10.00
        val result = money * 5L

        assertEquals(5000L, result.amount) // $50.00
        assertEquals(usd.id, result.currency.id)
    }

    @Test
    fun times_int_multipliesAmount() {
        val money = Money(1000, usd) // $10.00
        val result = money * 3

        assertEquals(3000L, result.amount) // $30.00
        assertEquals(usd.id, result.currency.id)
    }

    @Test
    fun div_long_dividesAmount() {
        val money = Money(10000, usd) // $100.00
        val result = money / 4L

        assertEquals(2500L, result.amount) // $25.00
        assertEquals(usd.id, result.currency.id)
    }

    @Test
    fun div_int_dividesAmount() {
        val money = Money(10000, usd) // $100.00
        val result = money / 2

        assertEquals(5000L, result.amount) // $50.00
        assertEquals(usd.id, result.currency.id)
    }

    @Test
    fun unaryMinus_negatesAmount() {
        val money = Money(10000, usd) // $100.00
        val result = -money

        assertEquals(-10000L, result.amount) // -$100.00
        assertEquals(usd.id, result.currency.id)
    }

    @Test
    fun compareTo_comparesCorrectly() {
        val a = Money(10000, usd)
        val b = Money(5000, usd)
        val c = Money(10000, usd)

        assertTrue(a > b)
        assertTrue(b < a)
        assertTrue(a >= c)
        assertTrue(a <= c)
        assertEquals(0, a.compareTo(c))
    }

    @Test
    fun compareTo_throwsWhenCurrenciesDontMatch() {
        val a = Money(10000, usd)
        val b = Money(5000, jpy)

        assertFailsWith<IllegalArgumentException> {
            a.compareTo(b)
        }
    }

    @Test
    fun isZero_returnsTrueForZeroAmount() {
        val money = Money(0, usd)
        assertTrue(money.isZero())
    }

    @Test
    fun isZero_returnsFalseForNonZeroAmount() {
        val money = Money(100, usd)
        assertFalse(money.isZero())
    }

    @Test
    fun isPositive_returnsTrueForPositiveAmount() {
        val money = Money(100, usd)
        assertTrue(money.isPositive())
    }

    @Test
    fun isPositive_returnsFalseForZeroAmount() {
        val money = Money(0, usd)
        assertFalse(money.isPositive())
    }

    @Test
    fun isPositive_returnsFalseForNegativeAmount() {
        val money = Money(-100, usd)
        assertFalse(money.isPositive())
    }

    @Test
    fun isNegative_returnsTrueForNegativeAmount() {
        val money = Money(-100, usd)
        assertTrue(money.isNegative())
    }

    @Test
    fun isNegative_returnsFalseForZeroAmount() {
        val money = Money(0, usd)
        assertFalse(money.isNegative())
    }

    @Test
    fun isNegative_returnsFalseForPositiveAmount() {
        val money = Money(100, usd)
        assertFalse(money.isNegative())
    }

    @Test
    fun abs_returnsAbsoluteValue() {
        val positive = Money(100, usd)
        val negative = Money(-100, usd)

        assertEquals(100L, positive.abs().amount)
        assertEquals(100L, negative.abs().amount)
    }

    @Test
    fun zero_createsZeroMoney() {
        val money = Money.zero(usd)
        assertEquals(0L, money.amount)
        assertEquals(usd.id, money.currency.id)
    }

    @Test
    fun roundTrip_displayValueConversion() {
        val original = BigDecimal("123.45")
        val money = Money.fromDisplayValue(original, usd)
        val converted = money.toDisplayValue()

        assertEquals(original.toString(), converted.toString())
    }

    @Test
    fun roundTrip_zeroDecimalPlaces() {
        val original = BigDecimal("1000")
        val money = Money.fromDisplayValue(original, jpy)
        val converted = money.toDisplayValue()

        assertEquals(original.toString(), converted.toString())
    }

    @Test
    fun roundTrip_threeDecimalPlaces() {
        val original = BigDecimal("123.456")
        val money = Money.fromDisplayValue(original, bhd)
        val converted = money.toDisplayValue()

        assertEquals(original.toString(), converted.toString())
    }
}
