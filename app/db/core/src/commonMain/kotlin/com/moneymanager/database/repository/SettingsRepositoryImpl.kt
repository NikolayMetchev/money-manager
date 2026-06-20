package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.sql.MoneyManagerDatabase
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class SettingsRepositoryImpl(
    database: MoneyManagerDatabase,
) : SettingsRepository {
    private val selectQueries = database.settingsSelectQueries
    private val writeQueries = database.settingsWriteQueries

    override fun getDefaultCurrencyId(): Flow<CurrencyId?> =
        selectQueries
            .selectDefaultCurrencyId()
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { it?.default_currency_id?.let(::CurrencyId) }

    override suspend fun setDefaultCurrencyId(currencyId: CurrencyId): Unit =
        withContext(Dispatchers.Default) {
            writeQueries.upsertDefaultCurrency(currencyId.id)
        }

    override fun getLastQifAccountId(): Flow<AccountId?> =
        selectQueries
            .selectLastQifAccountId()
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { it?.last_qif_account_id?.let(::AccountId) }

    override suspend fun setLastQifAccountId(accountId: AccountId): Unit =
        withContext(Dispatchers.Default) {
            writeQueries.upsertLastQifAccount(accountId.id)
        }
}
