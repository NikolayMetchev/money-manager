package com.moneymanager.domain.model

/**
 * ISO 4217 minor-unit divisor lookup — how many of a currency's minor units make one major unit,
 * per the ISO 4217 standard (e.g. 100 pence per GBP, 1 yen per JPY, 1000 fils per BHD).
 *
 * This is unrelated to [Asset.scaleFactor] (the app's own internal storage precision, uniform across
 * every currency — see [CurrencyScaleFactors]). It exists solely to interpret a raw integer amount a
 * bank API reports in its native minor units (e.g. Monzo's `1234` meaning `£12.34`): that integer must
 * first be converted to a decimal display value using the currency's *real* ISO divisor before
 * [Money.fromDisplayValue] scales it into the app's storage precision — using the storage scale
 * factor directly here would misinterpret the provider's minor-unit width.
 */
object IsoMinorUnitDivisors {
    /** Default divisor for currencies not explicitly listed (2 decimal places). */
    const val DEFAULT_DIVISOR = 100L

    private val divisors =
        mapOf(
            // 0 decimal places
            "BIF" to 1L,
            "CLP" to 1L,
            "DJF" to 1L,
            "GNF" to 1L,
            "ISK" to 1L,
            "JPY" to 1L,
            "KMF" to 1L,
            "KRW" to 1L,
            "PYG" to 1L,
            "RWF" to 1L,
            "UGX" to 1L,
            "UYI" to 1L,
            "VND" to 1L,
            "VUV" to 1L,
            "XAF" to 1L,
            "XOF" to 1L,
            "XPF" to 1L,
            // 3 decimal places
            "BHD" to 1000L,
            "IQD" to 1000L,
            "JOD" to 1000L,
            "KWD" to 1000L,
            "LYD" to 1000L,
            "OMR" to 1000L,
            "TND" to 1000L,
        )

    /** The ISO 4217 minor-unit divisor for [currencyCode] (e.g. 100 for "GBP", 1 for "JPY"). */
    fun getDivisor(currencyCode: String): Long = divisors[currencyCode.uppercase()] ?: DEFAULT_DIVISOR
}
