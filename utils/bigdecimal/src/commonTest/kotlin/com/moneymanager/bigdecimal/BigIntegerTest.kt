package com.moneymanager.bigdecimal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Pins java.math.BigInteger's semantics; see [BigDecimalSemanticsTest]. */
class BigIntegerTest {
    @Test
    fun arithmetic_isExactBeyondLong() {
        val big = BigInteger("9223372036854775807")
        assertEquals("9223372036854775808", (big + BigInteger(1L)).toString())
        assertEquals("-9223372036854775809", (-big - BigInteger(2L)).toString())
        assertEquals("85070591730234615847396907784232501249", (big * big).toString())
        assertEquals("5", BigInteger(-5L).abs().toString())
    }

    @Test
    fun constructor_acceptsSignedDigits() {
        assertEquals("42", BigInteger("+42").toString())
        assertEquals("-42", BigInteger("-42").toString())
        assertEquals("0", BigInteger("-0").toString())
        assertEquals("7", BigInteger("007").toString())
    }

    @Test
    fun constructor_rejectsNonIntegers() {
        val rejected = listOf("", "+", "-", " 1", "1.0", "1e3", "1,000", "--1", "0x10", "NaN")
        for (text in rejected) {
            assertFailsWith<NumberFormatException>("BigInteger(\"$text\")") { BigInteger(text) }
        }
    }

    @Test
    fun toLong_keepsLowSixtyFourBits() {
        assertEquals(Long.MAX_VALUE, BigInteger(Long.MAX_VALUE).toLong())
        assertEquals(Long.MIN_VALUE, BigInteger(Long.MIN_VALUE).toLong())
        assertEquals(Long.MIN_VALUE, BigInteger("9223372036854775808").toLong())
        assertEquals(Long.MAX_VALUE, BigInteger("-9223372036854775809").toLong())
        assertEquals(-1L, BigInteger("18446744073709551615").toLong())
        assertEquals(1L, BigInteger("-18446744073709551615").toLong())
    }

    @Test
    fun equalsAndCompareTo_followValue() {
        assertEquals(BigInteger("10"), BigInteger(10L))
        assertEquals(BigInteger("10").hashCode(), BigInteger(10L).hashCode())
        assertEquals(BigInteger.ZERO, BigInteger("-0"))
        assertEquals(-1, BigInteger(-1L).compareTo(BigInteger.ZERO).coerceIn(-1, 1))
    }

    @Test
    fun toBigDecimal_isWholeNumber() {
        assertEquals("123456789012345678901234567890", BigInteger("123456789012345678901234567890").toBigDecimal().toString())
    }
}
