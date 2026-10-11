package com.moneymanager.currency

import com.moneymanager.bigdecimal.BigDecimal

/**
 * Represents a currency with formatting capabilities.
 *
 * @param code ISO 4217 currency code (e.g., "USD", "GBP", "EUR")
 */
expect class Currency(
    code: String,
) {
    /**
     * The ISO 4217 currency code.
     */
    val code: String

    /**
     * Formats the given amount as a currency string.
     *
     * @param amount The numeric amount to format
     * @return The formatted currency string (e.g., "$1,234.56" for USD)
     */
    fun format(amount: Number): String

    /**
     * Formats the given amount as a currency string without converting through floating point.
     *
     * @param amount The decimal amount to format
     * @return The formatted currency string (e.g., "$1,234.56" for USD)
     */
    fun format(amount: BigDecimal): String

    companion object {
        /**
         * Returns the ISO 4217 currency code for the device's default locale,
         * or null if unavailable.
         */
        fun getDefaultCurrencyCode(): String?
    }
}
