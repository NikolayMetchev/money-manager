package com.moneymanager.domain.model

/**
 * ISO 4217 minor-unit lookup — how many decimal places a currency's minor unit has, per the ISO 4217
 * standard (e.g. 2 for GBP's pence, 0 for JPY, 3 for BHD's fils).
 *
 * This is unrelated to [Asset.scaleFactor] (the app's own internal storage precision, uniform across
 * every currency — see [CurrencyScaleFactors]). It exists solely to interpret a raw integer amount a
 * bank API reports in its native minor units (e.g. Monzo's `1234` meaning `£12.34`): that integer must
 * first be converted to a decimal display value using the currency's *real* ISO decimal places before
 * [Money.fromDisplayValue] scales it into the app's storage precision — using the storage scale
 * factor directly here would misinterpret the provider's minor-unit width.
 */
object IsoMinorUnitDecimals {
    /** Decimal places for currencies not explicitly listed. */
    const val DEFAULT_DECIMAL_PLACES = 2

    private val decimalPlaces =
        mapOf(
            // 0 decimal places
            "BIF" to 0,
            "CLP" to 0,
            "DJF" to 0,
            "GNF" to 0,
            "ISK" to 0,
            "JPY" to 0,
            "KMF" to 0,
            "KRW" to 0,
            "PYG" to 0,
            "RWF" to 0,
            "UGX" to 0,
            "UYI" to 0,
            "VND" to 0,
            "VUV" to 0,
            "XAF" to 0,
            "XOF" to 0,
            "XPF" to 0,
            // 3 decimal places
            "BHD" to 3,
            "IQD" to 3,
            "JOD" to 3,
            "KWD" to 3,
            "LYD" to 3,
            "OMR" to 3,
            "TND" to 3,
        )

    /** The ISO 4217 minor-unit decimal places for [currencyCode] (e.g. 2 for "GBP", 0 for "JPY"). */
    fun getDecimalPlaces(currencyCode: String): Int = decimalPlaces[currencyCode.uppercase()] ?: DEFAULT_DECIMAL_PLACES
}
