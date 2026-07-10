@file:Suppress("UnusedPrivateProperty") // False positive: expect class constructor params

package com.moneymanager.bigdecimal

/**
 * Platform-independent representation of an arbitrary-precision signed integer.
 *
 * Money amounts are stored as integer minor units (value × currency scale factor). Fiat fits in a
 * [Long], but crypto assets can use very large scale factors (e.g. ETH's 18 decimals), so amounts
 * and their sums must be arbitrary-precision to avoid silent overflow / precision loss.
 *
 * On JVM and Android platforms, this is implemented using java.math.BigInteger.
 */
expect class BigInteger : Comparable<BigInteger> {
    /**
     * Translates a Long into a BigInteger.
     */
    constructor(value: Long)

    /**
     * Translates the decimal-string representation of a BigInteger into a BigInteger.
     */
    constructor(value: String)

    /**
     * Returns a BigInteger whose value is (this + augend).
     */
    operator fun plus(augend: BigInteger): BigInteger

    /**
     * Returns a BigInteger whose value is (this - subtrahend).
     */
    operator fun minus(subtrahend: BigInteger): BigInteger

    /**
     * Returns a BigInteger whose value is (this × multiplicand).
     */
    operator fun times(multiplicand: BigInteger): BigInteger

    /**
     * Returns a BigInteger whose value is (this / divisor), truncated toward zero.
     */
    operator fun div(divisor: BigInteger): BigInteger

    /**
     * Returns a BigInteger whose value is -this.
     */
    operator fun unaryMinus(): BigInteger

    /**
     * Returns a BigInteger whose value is the absolute value of this BigInteger.
     */
    fun abs(): BigInteger

    /**
     * Compares this BigInteger with the specified BigInteger.
     */
    override operator fun compareTo(other: BigInteger): Int

    /**
     * Converts this BigInteger to a [BigDecimal] (scale 0).
     */
    fun toBigDecimal(): BigDecimal

    /**
     * Converts this BigInteger to a [Long], truncating the high bits if it does not fit.
     */
    fun toLong(): Long

    /**
     * Returns the decimal-string representation of this BigInteger.
     */
    override fun toString(): String

    companion object {
        /**
         * The value 0.
         */
        val ZERO: BigInteger
    }
}

/**
 * Converts this [BigDecimal] to a [BigInteger], throwing if it has a nonzero fractional part.
 * Used when turning a scaled display value into integer minor units without silent truncation.
 */
expect fun BigDecimal.toBigIntegerExact(): BigInteger
