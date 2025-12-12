package com.moneymanager.domain.model

import com.moneymanager.bigdecimal.BigDecimal

/**
 * Represents a monetary amount with a specific currency.
 *
 * Money amounts are stored as integers in the smallest currency unit (e.g., cents for USD, yen for JPY)
 * to avoid floating-point precision issues. The [Currency.scaleFactor] determines the conversion
 * between stored amounts and display amounts.
 *
 * For example:
 * - £123.45 is stored as amount=12345 with GBP (scaleFactor=100)
 * - ¥1000 is stored as amount=1000 with JPY (scaleFactor=1)
 * - BD 123.456 is stored as amount=123456 with BHD (scaleFactor=1000)
 *
 * @property amount The amount in the smallest currency unit (e.g., cents, pence, satoshis)
 * @property currency The currency of this monetary amount
 */
data class Money(
    val amount: Long,
    val currency: Currency,
) : Comparable<Money> {
    /**
     * Converts the stored amount to a display value using BigDecimal for precision.
     *
     * @return The display value as BigDecimal (e.g., 12345 with scaleFactor=100 becomes 123.45)
     */
    fun toDisplayValue(): BigDecimal {
        val amountDecimal = BigDecimal(amount)
        val scaleFactorDecimal = BigDecimal(currency.scaleFactor)
        return amountDecimal / scaleFactorDecimal
    }

    /**
     * Adds another Money amount to this one.
     *
     * @param other The Money amount to add
     * @return A new Money instance with the sum
     * @throws IllegalArgumentException if currencies don't match
     */
    operator fun plus(other: Money): Money {
        requireSameCurrency(other)
        return Money(amount + other.amount, currency)
    }

    /**
     * Subtracts another Money amount from this one.
     *
     * @param other The Money amount to subtract
     * @return A new Money instance with the difference
     * @throws IllegalArgumentException if currencies don't match
     */
    operator fun minus(other: Money): Money {
        requireSameCurrency(other)
        return Money(amount - other.amount, currency)
    }

    /**
     * Multiplies this Money amount by a scalar value.
     *
     * @param multiplier The value to multiply by
     * @return A new Money instance with the product
     */
    operator fun times(multiplier: Long): Money {
        return Money(amount * multiplier, currency)
    }

    /**
     * Multiplies this Money amount by a scalar value.
     *
     * @param multiplier The value to multiply by
     * @return A new Money instance with the product
     */
    operator fun times(multiplier: Int): Money {
        return Money(amount * multiplier, currency)
    }

    /**
     * Divides this Money amount by a scalar value.
     *
     * @param divisor The value to divide by
     * @return A new Money instance with the quotient
     */
    operator fun div(divisor: Long): Money {
        return Money(amount / divisor, currency)
    }

    /**
     * Divides this Money amount by a scalar value.
     *
     * @param divisor The value to divide by
     * @return A new Money instance with the quotient
     */
    operator fun div(divisor: Int): Money {
        return Money(amount / divisor, currency)
    }

    /**
     * Negates this Money amount.
     *
     * @return A new Money instance with the negated amount
     */
    operator fun unaryMinus(): Money {
        return Money(-amount, currency)
    }

    /**
     * Compares this Money amount with another.
     *
     * @param other The Money amount to compare with
     * @return Negative if this < other, zero if equal, positive if this > other
     * @throws IllegalArgumentException if currencies don't match
     */
    override fun compareTo(other: Money): Int {
        requireSameCurrency(other)
        return amount.compareTo(other.amount)
    }

    /**
     * Checks if this Money amount is zero.
     *
     * @return true if the amount is zero, false otherwise
     */
    fun isZero(): Boolean = amount == 0L

    /**
     * Checks if this Money amount is positive.
     *
     * @return true if the amount is greater than zero, false otherwise
     */
    fun isPositive(): Boolean = amount > 0L

    /**
     * Checks if this Money amount is negative.
     *
     * @return true if the amount is less than zero, false otherwise
     */
    fun isNegative(): Boolean = amount < 0L

    /**
     * Returns the absolute value of this Money amount.
     *
     * @return A new Money instance with the absolute amount
     */
    fun abs(): Money {
        return if (amount < 0) Money(-amount, currency) else this
    }

    private fun requireSameCurrency(other: Money) {
        require(currency.id == other.currency.id) {
            "Cannot perform operation on Money with different currencies: ${currency.code} and ${other.currency.code}"
        }
    }

    companion object {
        /**
         * Creates a Money instance from a display value (e.g., 123.45).
         *
         * @param displayValue The display value as BigDecimal
         * @param currency The currency of the amount
         * @return A new Money instance with the amount converted to the smallest currency unit
         */
        fun fromDisplayValue(
            displayValue: BigDecimal,
            currency: Currency,
        ): Money {
            val scaleFactorDecimal = BigDecimal(currency.scaleFactor)
            val amount = (displayValue * scaleFactorDecimal).toLong()
            return Money(amount, currency)
        }

        /**
         * Creates a Money instance from a display value (e.g., 123.45).
         *
         * @param displayValue The display value as Double
         * @param currency The currency of the amount
         * @return A new Money instance with the amount converted to the smallest currency unit
         */
        fun fromDisplayValue(
            displayValue: Double,
            currency: Currency,
        ): Money {
            return fromDisplayValue(BigDecimal(displayValue), currency)
        }

        /**
         * Creates a Money instance from a display value (e.g., "123.45").
         *
         * @param displayValue The display value as String
         * @param currency The currency of the amount
         * @return A new Money instance with the amount converted to the smallest currency unit
         */
        fun fromDisplayValue(
            displayValue: String,
            currency: Currency,
        ): Money {
            return fromDisplayValue(BigDecimal(displayValue), currency)
        }

        /**
         * Creates a zero Money instance for a given currency.
         *
         * @param currency The currency of the amount
         * @return A new Money instance with zero amount
         */
        fun zero(currency: Currency): Money {
            return Money(0L, currency)
        }
    }
}
