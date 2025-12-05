package com.moneymanager.ui.util

import com.moneymanager.domain.model.Currency
import com.moneymanager.currency.Currency as CurrencyFormatter

/**
 * Formats an amount using the currency symbol from the currency.
 *
 * @param amount The amount to format
 * @param currency The currency containing the ISO 4217 code
 * @return Formatted currency string (e.g., "$1,234.56" for USD)
 */
fun formatAmount(
    amount: Number,
    currency: Currency,
): String {
    return try {
        CurrencyFormatter(currency.code).format(amount)
    } catch (e: IllegalArgumentException) {
        // Fallback for unknown currency codes - just format as number with currency code
        String.format("%.2f %s", amount.toDouble(), currency.code)
    }
}
