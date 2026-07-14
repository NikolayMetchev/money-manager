package com.moneymanager.domain.repository.write

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.repository.SettingsReadRepository

interface SettingsWriteRepository : SettingsReadRepository {
    suspend fun setDefaultCurrencyId(currencyId: CurrencyId)

    suspend fun setLastQifAccountId(accountId: AccountId)

    suspend fun setSetupWizardCompleted(completed: Boolean)
}
