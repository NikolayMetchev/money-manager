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
 * plain decimal with trailing zeros trimmed and the ticker appended, e.g. "5 BNB", "0.5 BTC" —
 * every crypto asset carries 18 decimals, so padding to full precision would be unreadable.
 *
 * @param amount The amount to format
 * @param asset The asset the amount is denominated in
 */
fun formatAmount(
    amount: BigDecimal,
    asset: Asset,
): String =
    if (asset is CryptoAsset) {
        // BigDecimal.toString() already strips trailing zeros and never uses scientific notation.
        "$amount ${asset.code}"
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
 * @return Formatted string (e.g., "$1,234.56" for USD, "0.5 BTC" for a crypto asset)
 */
fun formatAmount(money: Money): String = formatAmount(money.toDisplayValue(), money.asset)
