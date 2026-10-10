package com.moneymanager.bigdecimal

import com.ionspin.kotlin.bignum.integer.BigInteger as IonBigInteger

private const val DIVISION_SCALE = 10

/**
 * Native implementation of BigDecimal, reproducing java.math.BigDecimal exactly.
 *
 * Like java.math, the value is [unscaled] × 10^-[scale]. ionspin only supplies the integer arithmetic:
 * its own BigDecimal rounds by significant digits and formats differently, so wrapping it would drift.
 */
actual class BigDecimal internal constructor(
    internal val unscaled: IonBigInteger,
    internal val scale: Int,
) : Comparable<BigDecimal> {
    actual constructor(value: Long) : this(IonBigInteger.fromLong(value), 0)

    actual constructor(value: Int) : this(IonBigInteger.fromInt(value), 0)

    actual constructor(value: String) : this(parseDecimal(value))

    private constructor(parsed: Pair<IonBigInteger, Int>) : this(parsed.first, parsed.second)

    actual operator fun plus(augend: BigDecimal): BigDecimal {
        val target = maxOf(scale, augend.scale)
        return BigDecimal(unscaledAt(target).add(augend.unscaledAt(target)), target)
    }

    actual operator fun minus(subtrahend: BigDecimal): BigDecimal {
        val target = maxOf(scale, subtrahend.scale)
        return BigDecimal(unscaledAt(target).subtract(subtrahend.unscaledAt(target)), target)
    }

    actual operator fun times(multiplicand: BigDecimal): BigDecimal =
        BigDecimal(unscaled.multiply(multiplicand.unscaled), scale + multiplicand.scale)

    /** java `divide(divisor, 10, RoundingMode.HALF_UP)`. */
    actual operator fun div(divisor: BigDecimal): BigDecimal {
        if (divisor.unscaled.isZero()) {
            throw ArithmeticException(if (unscaled.isZero()) "Division undefined" else "Division by zero")
        }
        val exponent = DIVISION_SCALE + divisor.scale - scale
        val numerator = if (exponent >= 0) unscaled.multiply(powerOfTen(exponent)) else unscaled
        val denominator = if (exponent >= 0) divisor.unscaled else divisor.unscaled.multiply(powerOfTen(-exponent))
        val (quotient, remainder) = numerator.divrem(denominator)
        val roundAwayFromZero = remainder.abs().multiply(IonBigInteger.TWO) >= denominator.abs()
        val rounded =
            if (roundAwayFromZero) {
                quotient.add(IonBigInteger.fromInt(numerator.signum() * denominator.signum()))
            } else {
                quotient
            }
        return BigDecimal(rounded, DIVISION_SCALE)
    }

    actual fun movePointLeft(n: Int): BigDecimal {
        if (n == 0) return this
        val newScale = scale + n
        return if (newScale < 0) BigDecimal(unscaled.multiply(powerOfTen(-newScale)), 0) else BigDecimal(unscaled, newScale)
    }

    actual operator fun unaryMinus(): BigDecimal = BigDecimal(unscaled.negate(), scale)

    actual fun abs(): BigDecimal = if (unscaled.signum() < 0) -this else this

    actual override operator fun compareTo(other: BigDecimal): Int {
        val target = maxOf(scale, other.scale)
        return unscaledAt(target).compareTo(other.unscaledAt(target))
    }

    /** java `longValue()`: drop the fraction (toward zero), then keep the low 64 bits. */
    actual fun toLong(): Long {
        val integral = if (scale > 0) unscaled.divide(powerOfTen(scale)) else unscaled.multiply(powerOfTen(-scale))
        return integral.longValue(exactRequired = false)
    }

    /** java `stripTrailingZeros().toPlainString()`. */
    actual override fun toString(): String {
        val stripped = stripTrailingZeros()
        val digits = stripped.unscaled.abs().toString()
        val sign = if (stripped.unscaled.signum() < 0) "-" else ""
        val s = stripped.scale
        return when {
            s <= 0 -> sign + digits + "0".repeat(-s)
            digits.length > s -> sign + digits.dropLast(s) + "." + digits.takeLast(s)
            else -> sign + "0." + "0".repeat(s - digits.length) + digits
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BigDecimal) return false
        return compareTo(other) == 0
    }

    override fun hashCode(): Int {
        val stripped = stripTrailingZeros()
        return 31 * stripped.unscaled.hashCode() + stripped.scale
    }

    /** The canonical form: no trailing zeros in [unscaled] (the scale may go negative), and zero at scale 0. */
    internal fun stripTrailingZeros(): BigDecimal {
        if (unscaled.isZero()) return if (scale == 0) this else ZERO
        var u = unscaled
        var s = scale
        while (true) {
            val (quotient, remainder) = u.divrem(IonBigInteger.TEN)
            if (!remainder.isZero()) break
            u = quotient
            s--
        }
        return if (s == scale) this else BigDecimal(u, s)
    }

    private fun unscaledAt(target: Int): IonBigInteger = if (target == scale) unscaled else unscaled.multiply(powerOfTen(target - scale))

    actual companion object {
        actual val ZERO: BigDecimal = BigDecimal(IonBigInteger.ZERO, 0)
        actual val ONE: BigDecimal = BigDecimal(IonBigInteger.ONE, 0)
        actual val TEN: BigDecimal = BigDecimal(IonBigInteger.TEN, 0)
    }
}

/**
 * java.math.BigDecimal(String)'s grammar: an optional sign, digits with at most one `.` (at least one
 * digit overall), then an optional `e`/`E` exponent with an optional sign. Any other text — whitespace,
 * grouping commas, `NaN` — is a [NumberFormatException], which callers rely on via `runCatching`.
 */
private fun parseDecimal(text: String): Pair<IonBigInteger, Int> {
    val exponentAt = text.indexOfFirst { it == 'e' || it == 'E' }
    val significand = if (exponentAt >= 0) text.substring(0, exponentAt) else text
    val exponent = if (exponentAt >= 0) parseExponent(text.substring(exponentAt + 1), text) else 0

    val negative = significand.startsWith('-')
    val body = if (negative || significand.startsWith('+')) significand.substring(1) else significand
    val point = body.indexOf('.')
    val integerPart = if (point >= 0) body.substring(0, point) else body
    val fractionPart = if (point >= 0) body.substring(point + 1) else ""
    if (integerPart.isEmpty() && fractionPart.isEmpty()) throw NumberFormatException("No digits in \"$text\"")

    val scale = fractionPart.length.toLong() - exponent
    if (scale !in Int.MIN_VALUE..Int.MAX_VALUE) throw NumberFormatException("Scale out of range in \"$text\"")
    val magnitude = IonBigInteger.parseString(asciiDigits(integerPart + fractionPart, text))
    return (if (negative) magnitude.negate() else magnitude) to scale.toInt()
}

private fun parseExponent(
    raw: String,
    text: String,
): Int {
    val unsigned = if (raw.startsWith('-') || raw.startsWith('+')) raw.substring(1) else raw
    if (unsigned.isEmpty()) throw NumberFormatException("No exponent digits in \"$text\"")
    val sign = if (raw.startsWith('-')) "-" else ""
    return (sign + asciiDigits(unsigned, text)).toIntOrNull() ?: throw NumberFormatException("Exponent overflow in \"$text\"")
}
