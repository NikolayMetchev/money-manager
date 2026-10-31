package com.moneymanager.domain.repository.write

import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.repository.SettingsReadRepository

interface SettingsWriteRepository : SettingsReadRepository {
    suspend fun setDefaultCurrencyId(currencyId: CurrencyId)

    suspend fun setDefaultLocation(code: String?)

    suspend fun setSetupWizardCompleted(completed: Boolean)
}
