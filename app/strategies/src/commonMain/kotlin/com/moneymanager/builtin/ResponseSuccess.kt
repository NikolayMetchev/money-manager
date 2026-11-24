package com.moneymanager.builtin

/** How an exchange's response body reports that a call succeeded; HTTP status alone is the default. */
internal sealed interface ResponseSuccess {
    /** Success when [field] equals [okValue] (Crypto.com `code` "0", Bybit `retCode` "0", Binance fiat `code` "000000"). */
    data class CodeField(
        val field: String,
        val okValue: String,
    ) : ResponseSuccess

    /** Success when the [field] array is empty or absent (Kraken's `error`). */
    data class ErrorArray(
        val field: String,
    ) : ResponseSuccess
}
