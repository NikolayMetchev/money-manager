package com.moneymanager.currency

import com.moneymanager.bigdecimal.BigDecimal

/**
 * Native and the browser have no locale data, so a currency formats as a plain decimal followed by its code
 * ("-1234.50 GBP"), padded to the ISO minor-unit width from the bundled [IsoCurrencies] table. A code the
 * bundle doesn't know (installed from a newer catalog list, or user-created) formats unpadded rather than
 * failing, since its minor unit is unknown.
 */
actual class Currency actual constructor(
    actual val code: String,
) {
    private val fractionDigits: Int = IsoCurrency.forCode(code)?.fractionDigits ?: UNKNOWN_FRACTION_DIGITS

    actual fun format(amount: Number): String = format(BigDecimal(amount.toString()))

    actual fun format(amount: BigDecimal): String = "${plainDecimal(amount.toString(), fractionDigits)} $code"

    actual companion object {
        private const val UNKNOWN_FRACTION_DIGITS = -1

        actual fun getDefaultCurrencyCode(): String? = null
    }
}
