package com.moneymanager.currency

import com.moneymanager.bigdecimal.BigDecimal

/**
 * Native has no locale data, so a currency formats as a plain decimal followed by its code
 * ("-1234.50 GBP"), padded to the ISO minor-unit width from the bundled [IsoCurrencies] table.
 */
actual class Currency actual constructor(
    actual val code: String,
) {
    private val fractionDigits: Int =
        requireNotNull(IsoCurrency.forCode(code)) { "Unknown ISO 4217 currency code: $code" }.fractionDigits

    actual fun format(amount: Number): String = format(BigDecimal(amount.toString()))

    actual fun format(amount: BigDecimal): String = "${plainDecimal(amount.toString(), fractionDigits)} $code"

    actual companion object {
        actual fun getDefaultCurrencyCode(): String? = null
    }
}
