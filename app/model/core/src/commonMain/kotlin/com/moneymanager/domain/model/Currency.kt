@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.domain.model

import kotlin.uuid.Uuid

/**
 * Represents a currency in the system.
 *
 * @property id Unique identifier for the currency (UUID)
 * @property code ISO 4217 currency code (e.g., "USD", "EUR", "GBP")
 * @property name Human-readable name of the currency (e.g., "US Dollar")
 * @property scaleFactor The factor used to convert between stored amounts and display amounts.
 *                       For example, 100 for currencies with 2 decimal places (USD, EUR, GBP),
 *                       1 for currencies with 0 decimal places (JPY, KRW),
 *                       1000 for currencies with 3 decimal places (BHD, KWD).
 *                       Stored amount = display amount × scaleFactor
 */
data class Currency(
    val id: CurrencyId,
    val code: String,
    val name: String,
    val scaleFactor: Int = 100,
)

@JvmInline
value class CurrencyId(val id: Uuid) {
    override fun toString() = id.toString()
}
