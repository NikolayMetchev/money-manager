package com.moneymanager.currency

import java.text.NumberFormat

actual class Currency actual constructor(actual val code: String) {
    private val javaCurrency: java.util.Currency = java.util.Currency.getInstance(code)
    private val formatter: NumberFormat =
        NumberFormat.getCurrencyInstance().apply {
            currency = javaCurrency
        }

    actual fun format(amount: Number): String = formatter.format(amount)
}
