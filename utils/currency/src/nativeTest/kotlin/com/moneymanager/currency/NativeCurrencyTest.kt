package com.moneymanager.currency

import com.moneymanager.bigdecimal.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

class NativeCurrencyTest {
    @Test
    fun `formats a plain decimal at the minor-unit width followed by the code`() {
        assertEquals("1234.50 GBP", Currency("GBP").format(BigDecimal("1234.5")))
        assertEquals("1234 JPY", Currency("JPY").format(1234))
        assertEquals("-50.25 USD", Currency("USD").format(-50.25))
    }

    @Test
    fun `formats a code outside the bundled table unpadded`() {
        assertEquals("12.5 ZZZ", Currency("ZZZ").format(BigDecimal("12.5")))
    }
}
