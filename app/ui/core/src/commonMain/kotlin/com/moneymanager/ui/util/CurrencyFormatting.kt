package com.moneymanager.ui.util

import com.moneymanager.currency.Currency
import com.moneymanager.domain.model.Asset

/**
 * Formats an amount using the currency symbol from the asset.
 *
 * @param amount The amount to format
 * @param asset The asset containing the currency code
 * @return Formatted currency string (e.g., "$1,234.56" for USD)
 */
fun formatAmount(
    amount: Number,
    asset: Asset,
): String {
    return try {
        Currency(asset.name).format(amount)
    } catch (e: IllegalArgumentException) {
        // Fallback for unknown currency codes - just format as number with asset name
        String.format("%.2f %s", amount.toDouble(), asset.name)
    }
}
