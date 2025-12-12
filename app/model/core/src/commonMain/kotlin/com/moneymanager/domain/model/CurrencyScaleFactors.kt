package com.moneymanager.domain.model

/**
 * ISO 4217 currency scale factor lookup utility.
 *
 * Provides scale factors for currencies based on ISO 4217 standard.
 * Scale factor is 10^decimalPlaces (e.g., for 2 decimal places, scale factor is 100).
 *
 * Most currencies use 2 decimal places (scale factor 100).
 * Some currencies use 0 decimal places (scale factor 1).
 * A few currencies use 3 decimal places (scale factor 1000).
 */
object CurrencyScaleFactors {
    /**
     * Default scale factor for currencies not explicitly listed.
     * Follows the ISO 4217 standard default of 2 decimal places.
     */
    const val DEFAULT_SCALE_FACTOR = 100

    /**
     * Map of ISO 4217 currency codes to their scale factors.
     * Only includes currencies that differ from the default (100).
     */
    private val scaleFactors =
        mapOf(
            // Currencies with 0 decimal places (scale factor = 1)
            // Burundian Franc
            "BIF" to 1,
            // Chilean Peso
            "CLP" to 1,
            // Djiboutian Franc
            "DJF" to 1,
            // Guinean Franc
            "GNF" to 1,
            // Icelandic Króna
            "ISK" to 1,
            // Japanese Yen
            "JPY" to 1,
            // Comorian Franc
            "KMF" to 1,
            // South Korean Won
            "KRW" to 1,
            // Paraguayan Guaraní
            "PYG" to 1,
            // Rwandan Franc
            "RWF" to 1,
            // Ugandan Shilling
            "UGX" to 1,
            // Uruguay Peso en Unidades Indexadas
            "UYI" to 1,
            // Vietnamese Đồng
            "VND" to 1,
            // Vanuatu Vatu
            "VUV" to 1,
            // Central African CFA Franc
            "XAF" to 1,
            // West African CFA Franc
            "XOF" to 1,
            // CFP Franc
            "XPF" to 1,
            // Currencies with 3 decimal places (scale factor = 1000)
            // Bahraini Dinar
            "BHD" to 1000,
            // Iraqi Dinar
            "IQD" to 1000,
            // Jordanian Dinar
            "JOD" to 1000,
            // Kuwaiti Dinar
            "KWD" to 1000,
            // Libyan Dinar
            "LYD" to 1000,
            // Omani Rial
            "OMR" to 1000,
            // Tunisian Dinar
            "TND" to 1000,
            // Cryptocurrencies and special currencies (4-8 decimal places)
            // Note: These are not officially ISO 4217 but included for completeness
            // BTC: 8 decimal places = 100,000,000 satoshis
            // ETH: 18 decimal places (impractical for INTEGER storage)
            // For now, we only include traditional fiat currencies
        )

    /**
     * Gets the scale factor for a given ISO 4217 currency code.
     *
     * @param currencyCode The ISO 4217 currency code (e.g., "USD", "JPY", "BHD")
     * @return The scale factor for the currency (e.g., 100 for USD, 1 for JPY, 1000 for BHD)
     */
    fun getScaleFactor(currencyCode: String): Int {
        return scaleFactors[currencyCode.uppercase()] ?: DEFAULT_SCALE_FACTOR
    }

    /**
     * Gets the number of decimal places for a given ISO 4217 currency code.
     *
     * @param currencyCode The ISO 4217 currency code (e.g., "USD", "JPY", "BHD")
     * @return The number of decimal places for the currency (e.g., 2 for USD, 0 for JPY, 3 for BHD)
     */
    fun getDecimalPlaces(currencyCode: String): Int {
        val scaleFactor = getScaleFactor(currencyCode)
        return when (scaleFactor) {
            1 -> 0
            10 -> 1
            100 -> 2
            1000 -> 3
            10000 -> 4
            else -> {
                // Calculate decimal places from scale factor
                var factor = scaleFactor
                var places = 0
                while (factor > 1) {
                    factor /= 10
                    places++
                }
                places
            }
        }
    }
}
