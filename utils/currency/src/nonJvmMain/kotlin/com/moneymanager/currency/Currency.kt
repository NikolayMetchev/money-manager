package com.moneymanager.currency

import com.moneymanager.bigdecimal.BigDecimal

/**
 * Native and the browser have no locale data, so a currency formats as a plain decimal followed by its code
 * ("-1234.50 GBP"), padded to [fractionDigits], the minor unit the database's currency records (installed with the
 * catalog's currency list). A currency without one formats unpadded rather than failing.
 */
actual class Currency actual constructor(
    actual val code: String,
    private val fractionDigits: Int?,
) {
    actual fun format(amount: Number): String = format(BigDecimal(amount.toString()))

    actual fun format(amount: BigDecimal): String = "${plainDecimal(amount.toString(), fractionDigits ?: UNPADDED)} $code"

    actual companion object {
        private const val UNPADDED = -1

        actual fun getDefaultCurrencyCode(): String? = null
    }
}
