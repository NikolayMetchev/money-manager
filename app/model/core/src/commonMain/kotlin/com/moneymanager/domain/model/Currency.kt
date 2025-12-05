@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.domain.model

import kotlin.uuid.Uuid

/**
 * Represents a currency in the system.
 *
 * @property id Unique identifier for the currency (UUID)
 * @property code ISO 4217 currency code (e.g., "USD", "EUR", "GBP")
 * @property name Human-readable name of the currency (e.g., "US Dollar")
 */
data class Currency(
    val id: CurrencyId,
    val code: String,
    val name: String,
)

@JvmInline
value class CurrencyId(val uuid: Uuid)
