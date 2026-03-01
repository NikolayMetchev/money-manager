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
     * The display name of the currency (e.g., "US Dollar" for USD).
     */
    val displayName: String

    /**
     * Formats the given amount as a currency string.
     *
     * @param amount The numeric amount to format
     * @return The formatted currency string (e.g., "$1,234.56" for USD)
     */
    fun format(amount: Number): String

    companion object {
        /**
         * Returns all available ISO 4217 currencies.
         *
         * @return List of all available currencies sorted by code
         */
        fun getAllCurrencies(): List<Currency>

        /**
         * Returns the ISO 4217 currency code for the device's default locale,
         * or null if unavailable.
         */
        fun getDefaultCurrencyCode(): String?
    }
}
