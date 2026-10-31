package com.moneymanager.domain.repository

import com.moneymanager.domain.model.CurrencyId
import kotlinx.coroutines.flow.Flow

interface SettingsReadRepository {
    fun getDefaultCurrencyId(): Flow<CurrencyId?>

    /** The holiday calendar code of accounts without a location of their own, if one was chosen. */
    fun getDefaultLocation(): Flow<String?>

    /** False until the setup wizard has been finished (or skipped) for this database. */
    fun isSetupWizardCompleted(): Flow<Boolean>
}
