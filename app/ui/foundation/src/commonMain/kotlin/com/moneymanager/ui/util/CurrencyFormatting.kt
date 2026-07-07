package com.moneymanager.ui.util

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.domain.model.Asset
import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.model.Money
import com.moneymanager.currency.Currency as CurrencyFormatter

/**
 * Formats an amount denominated in [asset].
 *
 * Fiat assets are formatted with the locale currency symbol via [CurrencyFormatter] (ISO 4217).
 * Crypto assets are not ISO 4217 (java.util.Currency throws on them), so they are formatted as a
 * plain decimal padded to the asset's precision with the ticker appended, e.g. "5.00000000 BNB".
 *
 * @param amount The amount to format
 * @param asset The asset the amount is denominated in
 */
fun formatAmount(
    amount: BigDecimal,
    asset: Asset,
): String =
    if (asset is CryptoAsset) {
        "${amount.toPlainStringWithDecimals(decimalPlacesOf(asset.scaleFactor))} ${asset.code}"
    } else {
        try {
            CurrencyFormatter(asset.code).format(amount)
        } catch (_: IllegalArgumentException) {
            // Fallback for unknown currency codes - just format as number with currency code
            "$amount ${asset.code}"
        }
    }

/**
 * Formats a Money value using the embedded asset.
 *
 * @param money The Money value to format
 * @return Formatted string (e.g., "$1,234.56" for USD, "5.00000000 BNB" for a crypto asset)
 */
fun formatAmount(money: Money): String = formatAmount(money.toDisplayValue(), money.currency)

/** Number of decimal places implied by a scale factor (e.g. 100 -> 2, 1e8 -> 8). */
private fun decimalPlacesOf(scaleFactor: Long): Int {
    var factor = scaleFactor
    var places = 0
    while (factor > 1) {
        factor /= 10
        places++
    }
    return places
}

/** Formats [this] with exactly [decimals] fractional digits (zero-padded). */
private fun BigDecimal.toPlainStringWithDecimals(decimals: Int): String {
    val s = toString()
    if (decimals == 0) return s.substringBefore('.')
    val dot = s.indexOf('.')
    if (dot < 0) return s + "." + "0".repeat(decimals)
    val frac = s.length - dot - 1
    return when {
        frac == decimals -> s
        frac < decimals -> s + "0".repeat(decimals - frac)
        else -> s.substring(0, dot + 1 + decimals)
    }
}
