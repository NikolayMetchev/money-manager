package com.moneymanager.database.repository.write

import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.repository.SettingsReadRepository
import com.moneymanager.domain.repository.write.SettingsWriteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SettingsWriteRepositoryImpl(
    database: MoneyManagerDatabaseWrapper,
    reader: SettingsReadRepository,
) : SettingsWriteRepository,
    SettingsReadRepository by reader {
    private val writeQueries = database.settingsWriteQueries

    override suspend fun setDefaultCurrencyId(currencyId: CurrencyId): Unit =
        withContext(Dispatchers.Default) {
            writeQueries.upsertDefaultCurrency(currencyId.id)
        }

    override suspend fun setLastQifAccountId(accountId: AccountId): Unit =
        withContext(Dispatchers.Default) {
            writeQueries.upsertLastQifAccount(accountId.id)
        }

    override suspend fun setSetupWizardCompleted(completed: Boolean): Unit =
        withContext(Dispatchers.Default) {
            writeQueries.upsertSetupWizardCompleted(if (completed) 1L else 0L)
        }
}
