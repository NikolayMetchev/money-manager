@file:Suppress("UnusedPrivateProperty") // False positive: value is used throughout

package com.moneymanager.bigdecimal

import java.math.RoundingMode

/**
 * JVM and Android implementation of BigDecimal using java.math.BigDecimal.
 */
actual class BigDecimal : Comparable<BigDecimal> {
    private val value: java.math.BigDecimal

    actual constructor(value: Long) {
        this.value = java.math.BigDecimal(value)
    }

    actual constructor(value: Int) {
        this.value = java.math.BigDecimal(value)
    }

    actual constructor(value: Double) {
        this.value = java.math.BigDecimal(value)
    }

    actual constructor(value: String) {
        this.value = java.math.BigDecimal(value)
    }

    internal constructor(value: java.math.BigDecimal) {
        this.value = value
    }

    actual operator fun plus(augend: BigDecimal): BigDecimal = BigDecimal(value.add(augend.value))

    actual operator fun minus(subtrahend: BigDecimal): BigDecimal = BigDecimal(value.subtract(subtrahend.value))

    actual operator fun times(multiplicand: BigDecimal): BigDecimal = BigDecimal(value.multiply(multiplicand.value))

    actual operator fun div(divisor: BigDecimal): BigDecimal = BigDecimal(value.divide(divisor.value, 10, RoundingMode.HALF_UP))

    actual operator fun unaryMinus(): BigDecimal = BigDecimal(value.negate())

    actual fun abs(): BigDecimal = BigDecimal(value.abs())

    actual override operator fun compareTo(other: BigDecimal): Int = value.compareTo(other.value)

    actual fun toDouble(): Double = value.toDouble()

    actual fun toLong(): Long = value.toLong()

    actual override fun toString(): String = value.stripTrailingZeros().toPlainString()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BigDecimal) return false
        return value.compareTo(other.value) == 0
    }

    override fun hashCode(): Int = value.stripTrailingZeros().hashCode()

    actual companion object {
        actual val ZERO: BigDecimal = BigDecimal(java.math.BigDecimal.ZERO)
        actual val ONE: BigDecimal = BigDecimal(java.math.BigDecimal.ONE)
        actual val TEN: BigDecimal = BigDecimal(java.math.BigDecimal.TEN)
    }
}
