package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.sql.read.MoneyManagerDatabase
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.repository.SettingsReadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SettingsReadRepositoryImpl(
    database: MoneyManagerDatabase,
) : SettingsReadRepository {
    private val selectQueries = database.settingsSelectQueries

    override fun getDefaultCurrencyId(): Flow<CurrencyId?> =
        selectQueries
            .selectDefaultCurrencyId()
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { it?.default_currency_id?.let(::CurrencyId) }

    override fun getLastQifAccountId(): Flow<AccountId?> =
        selectQueries
            .selectLastQifAccountId()
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { it?.last_qif_account_id?.let(::AccountId) }
}
