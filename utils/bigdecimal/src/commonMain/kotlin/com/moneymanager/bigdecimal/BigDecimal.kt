package com.moneymanager.bigdecimal

/**
 * Platform-independent representation of an arbitrary-precision signed decimal number.
 * This class provides precise decimal arithmetic without floating-point precision loss,
 * making it suitable for financial calculations.
 *
 * On JVM and Android platforms, this is implemented using java.math.BigDecimal.
 */
expect class BigDecimal : Comparable<BigDecimal> {
    /**
     * Translates a Long into a BigDecimal.
     */
    constructor(value: Long)

    /**
     * Translates an Int into a BigDecimal.
     */
    constructor(value: Int)

    /**
     * Translates a Double into a BigDecimal.
     */
    constructor(value: Double)

    /**
     * Translates a String representation of a BigDecimal into a BigDecimal.
     */
    constructor(value: String)

    /**
     * Returns a BigDecimal whose value is (this + augend).
     */
    operator fun plus(augend: BigDecimal): BigDecimal

    /**
     * Returns a BigDecimal whose value is (this - subtrahend).
     */
    operator fun minus(subtrahend: BigDecimal): BigDecimal

    /**
     * Returns a BigDecimal whose value is (this × multiplicand).
     */
    operator fun times(multiplicand: BigDecimal): BigDecimal

    /**
     * Returns a BigDecimal whose value is (this / divisor).
     */
    operator fun div(divisor: BigDecimal): BigDecimal

    /**
     * Returns a BigDecimal whose value is -this.
     */
    operator fun unaryMinus(): BigDecimal

    /**
     * Returns a BigDecimal whose value is the absolute value of this BigDecimal.
     */
    fun abs(): BigDecimal

    /**
     * Compares this BigDecimal with the specified BigDecimal.
     */
    override operator fun compareTo(other: BigDecimal): Int

    /**
     * Converts this BigDecimal to a Double.
     */
    fun toDouble(): Double

    /**
     * Converts this BigDecimal to a Long.
     */
    fun toLong(): Long

    /**
     * Returns the string representation of this BigDecimal.
     */
    override fun toString(): String

    companion object {
        /**
         * The value 0, with a scale of 0.
         */
        val ZERO: BigDecimal

        /**
         * The value 1, with a scale of 0.
         */
        val ONE: BigDecimal

        /**
         * The value 10, with a scale of 0.
         */
        val TEN: BigDecimal
    }
}
