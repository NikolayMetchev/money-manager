package com.moneymanager.domain.repository

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CurrencyId
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    fun getDefaultCurrencyId(): Flow<CurrencyId?>

    suspend fun setDefaultCurrencyId(currencyId: CurrencyId)

    /** The source account used by the most recent QIF import, for pre-selecting it next time. */
    fun getLastQifAccountId(): Flow<AccountId?>

    suspend fun setLastQifAccountId(accountId: AccountId)
}
