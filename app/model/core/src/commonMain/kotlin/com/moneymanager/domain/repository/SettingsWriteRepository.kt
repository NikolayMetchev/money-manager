package com.moneymanager.domain.repository

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CurrencyId

interface SettingsWriteRepository : SettingsReadRepository {
    suspend fun setDefaultCurrencyId(currencyId: CurrencyId)

    suspend fun setLastQifAccountId(accountId: AccountId)

    suspend fun setSetupWizardCompleted(completed: Boolean)
}
