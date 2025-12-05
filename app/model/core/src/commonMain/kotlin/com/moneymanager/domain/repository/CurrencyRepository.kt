@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.domain.repository

import com.moneymanager.domain.model.Currency
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

interface CurrencyRepository {
    fun getAllCurrencies(): Flow<List<Currency>>

    fun getCurrencyById(id: Uuid): Flow<Currency?>

    fun getCurrencyByCode(code: String): Flow<Currency?>

    /**
     * Creates a new currency or returns the existing one if a currency with the same code exists.
     * @param code The ISO 4217 currency code (e.g., "USD")
     * @param name The human-readable name of the currency (e.g., "US Dollar")
     * @return The UUID of the created or existing currency
     */
    suspend fun upsertCurrencyByCode(
        code: String,
        name: String,
    ): Uuid

    suspend fun updateCurrency(currency: Currency)

    suspend fun deleteCurrency(id: Uuid)
}
