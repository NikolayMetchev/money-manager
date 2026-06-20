@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.domain.repository

import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.Source

interface CurrencyWriteRepository : CurrencyReadRepository {
    /**
     * Creates a new currency or returns the existing one if a currency with the same code exists.
     * @param code The ISO 4217 currency code (e.g., "USD")
     * @param name The human-readable name of the currency (e.g., "US Dollar")
     * @return The CurrencyId of the created or existing currency
     */
    suspend fun upsertCurrencyByCode(
        code: String,
        name: String,
        source: Source,
    ): CurrencyId

    suspend fun updateCurrency(
        currency: Currency,
        source: Source,
    )

    suspend fun deleteCurrency(id: CurrencyId)
}
