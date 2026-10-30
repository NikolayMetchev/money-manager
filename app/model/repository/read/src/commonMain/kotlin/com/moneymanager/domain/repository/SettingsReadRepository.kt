package com.moneymanager.domain.repository

import com.moneymanager.domain.model.CurrencyId
import kotlinx.coroutines.flow.Flow

interface SettingsReadRepository {
    fun getDefaultCurrencyId(): Flow<CurrencyId?>

    /** False until the setup wizard has been finished (or skipped) for this database. */
    fun isSetupWizardCompleted(): Flow<Boolean>
}
