package com.moneymanager.domain.repository

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CurrencyId
import kotlinx.coroutines.flow.Flow

interface SettingsReadRepository {
    fun getDefaultCurrencyId(): Flow<CurrencyId?>

    /** The source account used by the most recent QIF import, for pre-selecting it next time. */
    fun getLastQifAccountId(): Flow<AccountId?>
}
