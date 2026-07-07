package com.moneymanager.domain.model

import kotlinx.serialization.Serializable

/**
 * Represents a fiat currency in the system — one [Asset] class.
 *
 * @property id Unique identifier for the currency, drawn from the shared `asset` id space
 * @property code ISO 4217 currency code (e.g., "USD", "EUR", "GBP")
 * @property name Human-readable name of the currency (e.g., "US Dollar")
 * @property scaleFactor The factor used to convert between stored amounts and display amounts.
 *                       For example, 100 for currencies with 2 decimal places (USD, EUR, GBP),
 *                       1 for currencies with 0 decimal places (JPY, KRW),
 *                       1000 for currencies with 3 decimal places (BHD, KWD).
 *                       Stored amount = display amount × scaleFactor
 */
data class Currency(
    override val id: CurrencyId,
    val revisionId: Long = 1,
    override val code: String,
    override val name: String,
    override val scaleFactor: Long = 100,
) : Asset

@Serializable
@JvmInline
value class CurrencyId(
    override val id: Long,
) : AssetId {
    override fun toString() = id.toString()
}
