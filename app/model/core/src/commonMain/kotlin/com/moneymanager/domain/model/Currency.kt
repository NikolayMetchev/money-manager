package com.moneymanager.domain.model

import kotlinx.serialization.Serializable

/**
 * Represents a fiat currency in the system — one [Asset] class.
 *
 * @property id Unique identifier for the currency, drawn from the shared `asset` id space
 * @property code ISO 4217 currency code (e.g., "USD", "EUR", "GBP")
 * @property name Human-readable name of the currency (e.g., "US Dollar")
 * @property scaleFactor The factor used to convert between stored amounts and display amounts:
 *                       stored amount = display amount × scaleFactor. Currencies created by the
 *                       app are all seeded at [CurrencyScaleFactors.DEFAULT_SCALE_FACTOR], *not*
 *                       at their ISO 4217 decimal-place count — see [CurrencyScaleFactors] for why.
 *                       There is deliberately no default: an implicit scale is how amounts end up
 *                       rounded to a precision no real database uses.
 */
data class Currency(
    override val id: CurrencyId,
    val revisionId: Long = 1,
    override val code: String,
    override val name: String,
    override val scaleFactor: Long,
) : Asset

@Serializable
@JvmInline
value class CurrencyId(
    override val id: Long,
) : AssetId {
    override fun toString() = id.toString()
}
