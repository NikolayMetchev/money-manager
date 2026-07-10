@file:Suppress("UnusedPrivateProperty") // False positive: value is used throughout

package com.moneymanager.bigdecimal

/**
 * JVM and Android implementation of BigInteger using java.math.BigInteger.
 */
actual class BigInteger : Comparable<BigInteger> {
    internal val value: java.math.BigInteger

    actual constructor(value: Long) {
        this.value = java.math.BigInteger.valueOf(value)
    }

    actual constructor(value: String) {
        this.value = java.math.BigInteger(value)
    }

    internal constructor(value: java.math.BigInteger) {
        this.value = value
    }

    actual operator fun plus(augend: BigInteger): BigInteger = BigInteger(value.add(augend.value))

    actual operator fun minus(subtrahend: BigInteger): BigInteger = BigInteger(value.subtract(subtrahend.value))

    actual operator fun times(multiplicand: BigInteger): BigInteger = BigInteger(value.multiply(multiplicand.value))

    actual operator fun div(divisor: BigInteger): BigInteger = BigInteger(value.divide(divisor.value))

    actual operator fun unaryMinus(): BigInteger = BigInteger(value.negate())

    actual fun abs(): BigInteger = BigInteger(value.abs())

    actual override operator fun compareTo(other: BigInteger): Int = value.compareTo(other.value)

    actual fun toBigDecimal(): BigDecimal = BigDecimal(value.toString())

    actual fun toLong(): Long = value.toLong()

    actual override fun toString(): String = value.toString()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BigInteger) return false
        return value == other.value
    }

    override fun hashCode(): Int = value.hashCode()

    actual companion object {
        actual val ZERO: BigInteger = BigInteger(java.math.BigInteger.ZERO)
    }
}

/**
 * Converts this [BigDecimal] to a [BigInteger], throwing [ArithmeticException] if it has a nonzero
 * fractional part. Reconstructs the java value from the plain string so no internal access is needed.
 */
actual fun BigDecimal.toBigIntegerExact(): BigInteger = BigInteger(java.math.BigDecimal(toString()).toBigIntegerExact())

/**
 * Converts this [BigDecimal] to a [BigInteger], discarding any fractional part (truncation toward
 * zero). Reconstructs the java value from the plain string so no internal access is needed.
 */
actual fun BigDecimal.toBigIntegerTruncated(): BigInteger = BigInteger(java.math.BigDecimal(toString()).toBigInteger())
