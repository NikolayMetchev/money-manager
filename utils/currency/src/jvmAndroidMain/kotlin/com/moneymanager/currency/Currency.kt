@file:Suppress("UnusedPrivateProperty") // False positive: javaCurrency and formatter are used

package com.moneymanager.currency

import java.text.NumberFormat
import java.util.Locale
import java.util.Currency as JavaCurrency

actual class Currency actual constructor(actual val code: String) {
    private val javaCurrency: JavaCurrency = JavaCurrency.getInstance(code)
    private val formatter: NumberFormat =
        NumberFormat.getCurrencyInstance().apply {
            currency = javaCurrency
        }

    actual val displayName: String
        get() = javaCurrency.displayName

    actual fun format(amount: Number): String = formatter.format(amount)

    actual companion object {
        actual fun getAllCurrencies(): List<Currency> =
            JavaCurrency.getAvailableCurrencies()
                .map { Currency(it.currencyCode) }
                .sortedBy { it.code }

        actual fun getDefaultCurrencyCode(): String? =
            try {
                JavaCurrency.getInstance(Locale.getDefault()).currencyCode
            } catch (_: IllegalArgumentException) {
                null
            }
    }
}
