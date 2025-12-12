package com.moneymanager.bigdecimal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BigDecimalTest {
    @Test
    fun constructor_fromLong_createsCorrectValue() {
        val value = BigDecimal(12345L)
        assertEquals("12345", value.toString())
    }

    @Test
    fun constructor_fromInt_createsCorrectValue() {
        val value = BigDecimal(100)
        assertEquals("100", value.toString())
    }

    @Test
    fun constructor_fromString_createsCorrectValue() {
        val value = BigDecimal("123.45")
        assertEquals("123.45", value.toString())
    }

    @Test
    fun plus_addsTwoValues() {
        val a = BigDecimal("100.50")
        val b = BigDecimal("50.25")
        val result = a + b
        assertEquals("150.75", result.toString())
    }

    @Test
    fun minus_subtractsTwoValues() {
        val a = BigDecimal("100.50")
        val b = BigDecimal("50.25")
        val result = a - b
        assertEquals("50.25", result.toString())
    }

    @Test
    fun times_multipliesTwoValues() {
        val a = BigDecimal("10.5")
        val b = BigDecimal("2")
        val result = a * b
        assertEquals("21", result.toString())
    }

    @Test
    fun div_dividesTwoValues() {
        val a = BigDecimal("100")
        val b = BigDecimal("4")
        val result = a / b
        assertEquals("25", result.toString())
    }

    @Test
    fun unaryMinus_negatesValue() {
        val a = BigDecimal("100.50")
        val result = -a
        assertEquals("-100.5", result.toString())
    }

    @Test
    fun compareTo_comparesValues() {
        val a = BigDecimal("100")
        val b = BigDecimal("50")
        val c = BigDecimal("100")

        assertTrue(a > b)
        assertTrue(b < a)
        assertTrue(a >= c)
        assertTrue(a <= c)
        assertEquals(0, a.compareTo(c))
    }

    @Test
    fun toDouble_convertsToDouble() {
        val value = BigDecimal("123.45")
        assertEquals(123.45, value.toDouble())
    }

    @Test
    fun toLong_convertsToLong() {
        val value = BigDecimal("12345")
        assertEquals(12345L, value.toLong())
    }

    @Test
    fun constants_haveCorrectValues() {
        assertEquals("0", BigDecimal.ZERO.toString())
        assertEquals("1", BigDecimal.ONE.toString())
        assertEquals("10", BigDecimal.TEN.toString())
    }

    @Test
    fun divisionByScaleFactor_maintainsPrecision() {
        // Simulate converting 12345 pence to pounds (£123.45)
        val pence = BigDecimal(12345L)
        val scaleFactor = BigDecimal(100)
        val pounds = pence / scaleFactor
        assertEquals("123.45", pounds.toString())
    }

    @Test
    fun multiplicationByScaleFactor_maintainsPrecision() {
        // Simulate converting £123.45 to pence (12345)
        val pounds = BigDecimal("123.45")
        val scaleFactor = BigDecimal(100)
        val pence = pounds * scaleFactor
        assertEquals(12345L, pence.toLong())
    }
}
