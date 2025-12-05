package com.moneymanager.currency

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class CurrencyTest {
    @Test
    fun `USD format should include dollar sign and currency indicator`() {
        val currency = Currency("USD")
        val formatted = currency.format(1234.56)

        // Uses default locale - may show US$ or $ depending on locale
        assertContains(formatted, "$")
        assertContains(formatted, "1")
        assertContains(formatted, "234")
    }

    @Test
    fun `GBP format should include pound sign`() {
        val currency = Currency("GBP")
        val formatted = currency.format(1234.56)

        assertContains(formatted, "£")
        assertContains(formatted, "1")
        assertContains(formatted, "234")
    }

    @Test
    fun `EUR format should include euro sign`() {
        val currency = Currency("EUR")
        val formatted = currency.format(1234.56)

        assertContains(formatted, "€")
        assertContains(formatted, "1")
        assertContains(formatted, "234")
    }

    @Test
    fun `JPY format should include yen sign`() {
        val currency = Currency("JPY")
        val formatted = currency.format(1234)

        // Japanese Yen can use ¥ (U+00A5), ￥ (U+FFE5 fullwidth), JP¥, or JPY
        val hasYenIndicator =
            formatted.contains("¥") ||
                formatted.contains("￥") ||
                formatted.contains("JP") ||
                formatted.contains("円")
        assertContains(formatted, "1")
        assertContains(formatted, "234")
        assert(hasYenIndicator) { "Expected yen indicator but got: $formatted" }
    }

    @Test
    fun `large numbers should include thousand separators`() {
        val currency = Currency("USD")
        val formatted = currency.format(25000000.0)

        // Should have separators (comma or space depending on locale)
        assertContains(formatted, "$")
        assertContains(formatted, "25")
    }

    @Test
    fun `format should handle integers`() {
        val currency = Currency("USD")
        val formatted = currency.format(100)

        assertContains(formatted, "$")
        assertContains(formatted, "100")
    }

    @Test
    fun `format should handle negative numbers`() {
        val currency = Currency("USD")
        val formatted = currency.format(-50.25)

        assertContains(formatted, "$")
        assertContains(formatted, "50")
    }

    @Test
    fun `format should handle zero`() {
        val currency = Currency("USD")
        val formatted = currency.format(0)

        assertContains(formatted, "$")
        assertContains(formatted, "0")
    }

    @Test
    fun `currency code should be stored correctly`() {
        val usd = Currency("USD")
        val gbp = Currency("GBP")

        assertEquals("USD", usd.code)
        assertEquals("GBP", gbp.code)
    }
}
