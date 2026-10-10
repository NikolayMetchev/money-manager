package com.moneymanager.bigdecimal

import com.ionspin.kotlin.bignum.integer.BigInteger as IonBigInteger

/**
 * Native implementation of BigInteger over ionspin's BigInteger, matching java.math.BigInteger's
 * semantics: [toLong] keeps the low 64 bits.
 */
actual class BigInteger internal constructor(
    internal val value: IonBigInteger,
) : Comparable<BigInteger> {
    actual constructor(value: Long) : this(IonBigInteger.fromLong(value))

    actual constructor(value: String) : this(parseInteger(value))

    actual operator fun plus(augend: BigInteger): BigInteger = BigInteger(value.add(augend.value))

    actual operator fun minus(subtrahend: BigInteger): BigInteger = BigInteger(value.subtract(subtrahend.value))

    actual operator fun times(multiplicand: BigInteger): BigInteger = BigInteger(value.multiply(multiplicand.value))

    actual operator fun unaryMinus(): BigInteger = BigInteger(value.negate())

    actual fun abs(): BigInteger = BigInteger(value.abs())

    actual override operator fun compareTo(other: BigInteger): Int = value.compareTo(other.value)

    actual fun toBigDecimal(): BigDecimal = BigDecimal(value, 0)

    actual fun toLong(): Long = value.longValue(exactRequired = false)

    actual override fun toString(): String = value.toString()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BigInteger) return false
        return value == other.value
    }

    override fun hashCode(): Int = value.hashCode()

    actual companion object {
        actual val ZERO: BigInteger = BigInteger(IonBigInteger.ZERO)
    }
}

/**
 * Converts this [BigDecimal] to a [BigInteger], throwing [ArithmeticException] if it has a nonzero
 * fractional part.
 */
actual fun BigDecimal.toBigIntegerExact(): BigInteger {
    val stripped = stripTrailingZeros()
    if (stripped.scale > 0) throw ArithmeticException("Rounding necessary")
    return BigInteger(stripped.unscaled.multiply(powerOfTen(-stripped.scale)))
}

/**
 * java.math.BigInteger's decimal grammar: an optional sign then one or more digits (any Unicode decimal
 * digit, as `Character.digit` accepts). Checked here because ionspin's parser also accepts decimals.
 */
private fun parseInteger(text: String): IonBigInteger {
    val negative = text.startsWith('-')
    val digits = if (negative || text.startsWith('+')) text.substring(1) else text
    if (digits.isEmpty()) throw NumberFormatException("Zero length BigInteger: \"$text\"")
    val magnitude = IonBigInteger.parseString(asciiDigits(digits, text))
    return if (negative) magnitude.negate() else magnitude
}

/** Maps every char to its ASCII digit, or throws [NumberFormatException] if one isn't a decimal digit. */
internal fun asciiDigits(
    digits: String,
    original: String,
): String =
    buildString(digits.length) {
        for (c in digits) {
            if (!c.isDigit()) throw NumberFormatException("Illegal digit '$c' in \"$original\"")
            append('0' + c.digitToInt())
        }
    }

private val TEN_ION: IonBigInteger = IonBigInteger.TEN

internal fun powerOfTen(exponent: Int): IonBigInteger = if (exponent == 0) IonBigInteger.ONE else TEN_ION.pow(exponent)
