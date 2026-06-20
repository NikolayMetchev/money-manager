package com.moneymanager.domain.repository

import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import kotlinx.coroutines.flow.Flow

interface CurrencyReadRepository {
    fun getAllCurrencies(): Flow<List<Currency>>

    fun getCurrencyById(id: CurrencyId): Flow<Currency?>

    fun getCurrencyByCode(code: String): Flow<Currency?>
}
