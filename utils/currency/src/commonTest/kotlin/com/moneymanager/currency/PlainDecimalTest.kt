package com.moneymanager.currency

import kotlin.test.Test
import kotlin.test.assertEquals

class PlainDecimalTest {
    @Test
    fun `pads to the minor-unit width`() {
        assertEquals("1234.50", plainDecimal("1234.5", 2))
        assertEquals("1234", plainDecimal("1234", 0))
        assertEquals("0.00", plainDecimal("0", 2))
    }

    @Test
    fun `keeps extra precision rather than rounding`() {
        assertEquals("0.125", plainDecimal("0.125", 2))
    }

    @Test
    fun `expands exponent notation`() {
        assertEquals("1200.00", plainDecimal("1.2E+3", 2))
        assertEquals("25000000.00", plainDecimal("2.5E7", 2))
        assertEquals("0.0000005", plainDecimal("5E-7", 2))
    }

    @Test
    fun `keeps the sign except on zero`() {
        assertEquals("-50.25", plainDecimal("-50.25", 2))
        assertEquals("0.00", plainDecimal("-0.00", 2))
    }

    @Test
    fun `a currency without a minor unit is left as is`() {
        assertEquals("3.5", plainDecimal("3.5", -1))
    }
}
