package com.moneymanager.bigdecimal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * Pins java.math.BigDecimal's semantics for every platform. On JVM and Android the actual *is*
 * java.math, so these expectations are proven there; the native actual must reproduce them exactly,
 * because any drift changes import results.
 */
class BigDecimalSemanticsTest {
    @Test
    fun div_roundsHalfUpAtScaleTen() {
        assertEquals("0.3333333333", (BigDecimal(1) / BigDecimal(3)).toString())
        assertEquals("0.6666666667", (BigDecimal(2) / BigDecimal(3)).toString())
        assertEquals("-0.6666666667", (BigDecimal(-2) / BigDecimal(3)).toString())
        assertEquals("-0.6666666667", (BigDecimal(2) / BigDecimal(-3)).toString())
        assertEquals("0.6666666667", (BigDecimal(-2) / BigDecimal(-3)).toString())
    }

    @Test
    fun div_roundsExactHalvesAwayFromZero() {
        // 0.00000000005 sits exactly on the half at scale 10.
        assertEquals("0.0000000001", (BigDecimal("0.0000000001") / BigDecimal(2)).toString())
        assertEquals("-0.0000000001", (BigDecimal("-0.0000000001") / BigDecimal(2)).toString())
        assertEquals("0", (BigDecimal("0.00000000004") / BigDecimal(1)).toString())
        assertEquals("0.0000000001", (BigDecimal("0.00000000005") / BigDecimal(1)).toString())
        assertEquals("-0.0000000001", (BigDecimal("-0.00000000005") / BigDecimal(1)).toString())
        assertEquals("0", (BigDecimal("-0.00000000004") / BigDecimal(1)).toString())
    }

    @Test
    fun div_handlesDivisorWithLargerScaleThanDividend() {
        assertEquals("40000", (BigDecimal(100) / BigDecimal("0.0025")).toString())
        assertEquals("33.3333333333", (BigDecimal(1) / BigDecimal("0.03")).toString())
        assertEquals("0.0000000123", (BigDecimal("0.000000012345") / BigDecimal("1.0000")).toString())
    }

    @Test
    fun div_handlesNegativeScaleOperands() {
        assertEquals("250", (BigDecimal("1E+3") / BigDecimal(4)).toString())
        assertEquals("0.004", (BigDecimal(4) / BigDecimal("1E+3")).toString())
    }

    @Test
    fun div_byZeroThrowsArithmeticException() {
        assertFailsWith<ArithmeticException> { BigDecimal(1) / BigDecimal.ZERO }
        assertFailsWith<ArithmeticException> { BigDecimal.ZERO / BigDecimal("0.00") }
    }

    @Test
    fun toString_stripsTrailingZerosInPlainNotation() {
        assertEquals("100.5", BigDecimal("100.500").toString())
        assertEquals("100", BigDecimal("100").toString())
        assertEquals("100", BigDecimal("100.000").toString())
        assertEquals("1000", BigDecimal("1E+3").toString())
        assertEquals("1000", BigDecimal("1e3").toString())
        assertEquals("0.001", BigDecimal("1E-3").toString())
        assertEquals("0", BigDecimal("0.000").toString())
        assertEquals("0", BigDecimal("-0.0").toString())
        assertEquals("0", BigDecimal("0E+5").toString())
        assertEquals("-12.34", BigDecimal("-1234e-2").toString())
        assertEquals("0.0000000000000000000000001", BigDecimal("1E-25").toString())
        assertEquals("1" + "0".repeat(30), BigDecimal("1E+30").toString())
    }

    @Test
    fun constructor_acceptsJavaGrammar() {
        val accepted =
            mapOf(
                "+1" to "1",
                "-1" to "-1",
                ".5" to "0.5",
                "+.5" to "0.5",
                "-.5" to "-0.5",
                "5." to "5",
                "-0" to "0",
                "007.50" to "7.5",
                "1e5" to "100000",
                "1E+5" to "100000",
                "1.5e-1" to "0.15",
                "1e0005" to "100000",
                "123456789012345678901234567890.123456789012345678901234567890" to
                    "123456789012345678901234567890.12345678901234567890123456789",
            )
        for ((text, expected) in accepted) {
            assertEquals(expected, BigDecimal(text).toString(), "BigDecimal(\"$text\")")
        }
    }

    @Test
    fun constructor_rejectsEverythingElse() {
        val rejected =
            listOf(
                "",
                ".",
                "+",
                "-",
                " 1",
                "1 ",
                "1,000",
                "NaN",
                "Infinity",
                "1e",
                "1e+",
                "e5",
                "--1",
                "+-1",
                "1.2.3",
                "1e5e3",
                "1e5.0",
                "0x10",
                "1_000",
            )
        for (text in rejected) {
            assertFailsWith<NumberFormatException>("BigDecimal(\"$text\")") { BigDecimal(text) }
        }
    }

    @Test
    fun equals_ignoresScaleAndHashesConsistently() {
        assertEquals(BigDecimal("1.0"), BigDecimal("1.00"))
        assertEquals(BigDecimal("1.0").hashCode(), BigDecimal("1.00").hashCode())
        assertEquals(BigDecimal("100"), BigDecimal("1E+2"))
        assertEquals(BigDecimal("100").hashCode(), BigDecimal("1E+2").hashCode())
        assertEquals(BigDecimal.ZERO, BigDecimal("0.000"))
        assertEquals(BigDecimal.ZERO.hashCode(), BigDecimal("-0.000").hashCode())
        assertNotEquals(BigDecimal("1.01"), BigDecimal("1.1"))

        val byValue = mapOf(BigDecimal("2.50") to "two and a half")
        assertEquals("two and a half", byValue[BigDecimal("2.5")])
    }

    @Test
    fun compareTo_alignsScales() {
        assertEquals(0, BigDecimal("2.50").compareTo(BigDecimal("2.5")))
        assertEquals(-1, BigDecimal("2.49").compareTo(BigDecimal("2.5")).coerceIn(-1, 1))
        assertEquals(1, BigDecimal("1E+1").compareTo(BigDecimal("9.99")).coerceIn(-1, 1))
        assertEquals(-1, BigDecimal("-0.1").compareTo(BigDecimal.ZERO).coerceIn(-1, 1))
    }

    @Test
    fun arithmetic_isExactAcrossScales() {
        assertEquals("0.3", (BigDecimal("0.1") + BigDecimal("0.2")).toString())
        assertEquals("-0.1", (BigDecimal("0.1") - BigDecimal("0.2")).toString())
        assertEquals("0.02", (BigDecimal("0.1") * BigDecimal("0.2")).toString())
        assertEquals("1000.001", (BigDecimal("1E+3") + BigDecimal("0.001")).toString())
        assertEquals("12.5", BigDecimal("-12.50").abs().toString())
        assertEquals("12.5", (-BigDecimal("-12.50")).toString())
    }

    @Test
    fun eighteenDecimalScale_roundTripsMinorUnits() {
        // Every asset is stored at 1e18 (CurrencyScaleFactors), so this is the scale Money lives at.
        val scaleFactor = BigDecimal(1_000_000_000_000_000_000L)
        assertEquals(BigInteger(1L), (BigDecimal("0.000000000000000001") * scaleFactor).toBigIntegerExact())
        val units = BigInteger("123456789012345678901234567890")
        assertEquals(units, (units.toBigDecimal().movePointLeft(18) * scaleFactor).toBigIntegerExact())
        assertEquals("123456789012.34567890123456789", units.toBigDecimal().movePointLeft(18).toString())
        assertEquals("-0.000000000000000001", BigDecimal(-1).movePointLeft(18).toString())
    }

    @Test
    fun movePointLeft_withNegativeResultScaleIsWholeNumber() {
        assertEquals("1000", BigDecimal("1E+5").movePointLeft(2).toString())
        assertEquals(BigInteger(1000L), BigDecimal("1E+5").movePointLeft(2).toBigIntegerExact())
        assertEquals("12345", BigDecimal("123.45").movePointLeft(-2).toString())
    }

    @Test
    fun toBigIntegerExact_throwsOnFraction() {
        assertFailsWith<ArithmeticException> { BigDecimal("1.5").toBigIntegerExact() }
        assertFailsWith<ArithmeticException> { BigDecimal("0.000000000000000001").toBigIntegerExact() }
        assertEquals(BigInteger(2L), BigDecimal("2.000").toBigIntegerExact())
        assertEquals(BigInteger(-200L), BigDecimal("-2E+2").toBigIntegerExact())
    }

    @Test
    fun toLong_truncatesTowardZero() {
        assertEquals(1L, BigDecimal("1.9").toLong())
        assertEquals(-1L, BigDecimal("-1.9").toLong())
        assertEquals(0L, BigDecimal("-0.999").toLong())
        assertEquals(500L, BigDecimal("5E+2").toLong())
    }

    @Test
    fun toLong_keepsLowSixtyFourBitsOnOverflow() {
        assertEquals(Long.MAX_VALUE, BigDecimal(Long.MAX_VALUE.toString()).toLong())
        assertEquals(Long.MIN_VALUE, BigDecimal(Long.MIN_VALUE.toString()).toLong())
        assertEquals(Long.MIN_VALUE, BigDecimal("9223372036854775808").toLong())
        assertEquals(Long.MAX_VALUE, BigDecimal("-9223372036854775809").toLong())
        assertEquals(0L, BigDecimal("18446744073709551616").toLong())
        assertEquals(Long.MIN_VALUE, BigDecimal("9223372036854775808.75").toLong())
    }
}
