package com.moneymanager.domain.repository

import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import kotlinx.coroutines.flow.Flow

interface CurrencyReadRepository {
    fun getAllCurrencies(): Flow<List<Currency>>

    /**
     * Currencies the system created: seeded with the database or installed from the catalog's currency
     * list. Excludes currencies a user (or an import) created, so they never read as a change to the list.
     */
    fun getSystemCreatedCurrencies(): Flow<List<Currency>>

    fun getCurrencyById(id: CurrencyId): Flow<Currency?>

    fun getCurrencyByCode(code: String): Flow<Currency?>
}
