package com.moneymanager.domain.model

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.bigdecimal.BigInteger
import com.moneymanager.bigdecimal.toBigIntegerExact

/**
 * Represents a monetary amount denominated in a specific [Asset] (fiat [Currency] or [CryptoAsset]).
 *
 * Amounts are stored as arbitrary-precision integers in the asset's smallest unit (e.g. pence for
 * GBP, satoshis for BTC, wei for ETH) to avoid floating-point precision loss. The [Asset.scaleFactor]
 * determines the conversion between stored amounts and display amounts. A [BigInteger] (rather than a
 * [Long]) is required because high-precision crypto scale factors would otherwise overflow.
 *
 * The denominating asset is exposed as [currency] for historical reasons — it may be any [Asset],
 * fiat or crypto, not only a fiat [Currency].
 *
 * For example:
 * - £123.45 is stored as amount=12345 with GBP (scaleFactor=100)
 * - ¥1000 is stored as amount=1000 with JPY (scaleFactor=1)
 * - 0.5 BTC is stored as amount=5·10^17 with BTC (scaleFactor=10^18)
 *
 * @property amount The amount in the asset's smallest unit (pence, satoshis, wei, …)
 * @property currency The asset this monetary amount is denominated in
 */
data class Money(
    val amount: BigInteger,
    val currency: Asset,
) : Comparable<Money> {
    /** Convenience for constructing from a minor-unit amount that fits in a [Long]. */
    constructor(amount: Long, currency: Asset) : this(BigInteger(amount), currency)

    /** Convenience for constructing from a minor-unit amount that fits in an [Int]. */
    constructor(amount: Int, currency: Asset) : this(BigInteger(amount.toLong()), currency)

    /**
     * Converts the stored amount to a display value using BigDecimal for precision.
     *
     * Exact: shifts the decimal point by the asset's decimal count instead of dividing, because
     * [BigDecimal.div] rounds to a fixed scale and would corrupt high-precision crypto amounts.
     *
     * @return The display value as BigDecimal (e.g., 12345 with scaleFactor=100 becomes 123.45)
     */
    fun toDisplayValue(): BigDecimal = amount.toBigDecimal().movePointLeft(currency.decimalPlaces)

    /**
     * Adds another Money amount to this one.
     *
     * @throws IllegalArgumentException if assets don't match
     */
    operator fun plus(other: Money): Money {
        requireSameAsset(other)
        return Money(amount + other.amount, currency)
    }

    /**
     * Subtracts another Money amount from this one.
     *
     * @throws IllegalArgumentException if assets don't match
     */
    operator fun minus(other: Money): Money {
        requireSameAsset(other)
        return Money(amount - other.amount, currency)
    }

    /**
     * Multiplies this Money amount by a scalar value.
     */
    operator fun times(multiplier: Long): Money = Money(amount * BigInteger(multiplier), currency)

    /**
     * Multiplies this Money amount by a scalar value.
     */
    operator fun times(multiplier: Int): Money = times(multiplier.toLong())

    /**
     * Divides this Money amount by a scalar value (integer division, truncated toward zero).
     */
    operator fun div(divisor: Long): Money = Money(amount / BigInteger(divisor), currency)

    /**
     * Divides this Money amount by a scalar value (integer division, truncated toward zero).
     */
    operator fun div(divisor: Int): Money = div(divisor.toLong())

    /**
     * Negates this Money amount.
     */
    operator fun unaryMinus(): Money = Money(-amount, currency)

    /**
     * Compares this Money amount with another.
     *
     * @throws IllegalArgumentException if assets don't match
     */
    override fun compareTo(other: Money): Int {
        requireSameAsset(other)
        return amount.compareTo(other.amount)
    }

    /**
     * Checks if this Money amount is zero.
     */
    fun isZero(): Boolean = amount.compareTo(BigInteger.ZERO) == 0

    /**
     * Checks if this Money amount is positive.
     */
    fun isPositive(): Boolean = amount.compareTo(BigInteger.ZERO) > 0

    /**
     * Checks if this Money amount is negative.
     */
    fun isNegative(): Boolean = amount.compareTo(BigInteger.ZERO) < 0

    /**
     * Returns the absolute value of this Money amount.
     */
    fun abs(): Money = if (isNegative()) Money(amount.abs(), currency) else this

    private fun requireSameAsset(other: Money) {
        require(currency.id == other.currency.id) {
            "Cannot perform operation on Money with different assets: ${currency.code} and ${other.currency.code}"
        }
    }

    companion object {
        /**
         * Creates a Money instance from a display value (e.g., 123.45).
         *
         * The value is scaled to the asset's smallest unit exactly; it throws if [displayValue] has
         * more decimal places than the asset's [Asset.scaleFactor] can represent, so precision is
         * never silently lost.
         *
         * @throws ArithmeticException if the scaled value is not a whole number of minor units
         */
        fun fromDisplayValue(
            displayValue: BigDecimal,
            currency: Asset,
        ): Money {
            val scaled = displayValue * BigDecimal(currency.scaleFactor)
            return Money(scaled.toBigIntegerExact(), currency)
        }

        /**
         * Creates a Money instance from a display value (e.g., "123.45").
         */
        fun fromDisplayValue(
            displayValue: String,
            currency: Asset,
        ): Money = fromDisplayValue(BigDecimal(displayValue), currency)

        /**
         * Creates a zero Money instance for a given asset.
         */
        fun zero(currency: Asset): Money = Money(BigInteger.ZERO, currency)
    }
}
