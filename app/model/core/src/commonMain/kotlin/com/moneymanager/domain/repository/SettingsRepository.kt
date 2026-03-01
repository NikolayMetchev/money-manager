package com.moneymanager.domain.repository

import com.moneymanager.domain.model.CurrencyId
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    fun getDefaultCurrencyId(): Flow<CurrencyId?>

    suspend fun setDefaultCurrencyId(currencyId: CurrencyId)
}
