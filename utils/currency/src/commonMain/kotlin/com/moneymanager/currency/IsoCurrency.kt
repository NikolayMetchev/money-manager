package com.moneymanager.currency

/**
 * One ISO 4217 currency from the bundled table ([IsoCurrencies.all]).
 *
 * @param name English display name, as the JDK reports it
 * @param fractionDigits the ISO minor-unit width (2 for GBP, 0 for JPY), or -1 when the code has none
 *   (gold, SDR, testing codes), following `java.util.Currency.getDefaultFractionDigits`
 */
data class IsoCurrency(
    val code: String,
    val name: String,
    val fractionDigits: Int,
) {
    companion object {
        /** The bundled entry for [code], or null when it isn't an ISO 4217 code the table knows. */
        fun forCode(code: String): IsoCurrency? = byCode[code]

        private val byCode: Map<String, IsoCurrency> by lazy { IsoCurrencies.all.associateBy { it.code } }
    }
}
