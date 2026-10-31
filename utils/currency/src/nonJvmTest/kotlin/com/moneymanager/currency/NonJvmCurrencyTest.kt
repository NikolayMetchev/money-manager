package com.moneymanager.currency

import com.moneymanager.bigdecimal.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

class NonJvmCurrencyTest {
    @Test
    fun `formats a plain decimal at the minor-unit width followed by the code`() {
        assertEquals("1234.50 GBP", Currency("GBP", 2).format(BigDecimal("1234.5")))
        assertEquals("1234 JPY", Currency("JPY", 0).format(1234))
        assertEquals("-50.25 USD", Currency("USD", 2).format(-50.25))
    }

    @Test
    fun `formats a currency with no known minor unit unpadded`() {
        assertEquals("12.5 ZZZ", Currency("ZZZ").format(BigDecimal("12.5")))
    }
}
