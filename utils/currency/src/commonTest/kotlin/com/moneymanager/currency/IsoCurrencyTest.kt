package com.moneymanager.currency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IsoCurrencyTest {
    @Test
    fun `looks up bundled currencies by code`() {
        assertEquals(IsoCurrency("GBP", "British Pound", 2), IsoCurrency.forCode("GBP"))
        assertEquals(0, IsoCurrency.forCode("JPY")?.fractionDigits)
        assertNull(IsoCurrency.forCode("ZZZ"))
    }

    @Test
    fun `the bundled table is sorted by code without duplicates`() {
        val codes = IsoCurrencies.all.map { it.code }
        assertEquals(codes.sorted().distinct(), codes)
    }
}
