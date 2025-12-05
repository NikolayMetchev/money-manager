package com.moneymanager.currency

/**
 * Represents a currency with formatting capabilities.
 *
 * @param code ISO 4217 currency code (e.g., "USD", "GBP", "EUR")
 */
expect class Currency(code: String) {
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
}
