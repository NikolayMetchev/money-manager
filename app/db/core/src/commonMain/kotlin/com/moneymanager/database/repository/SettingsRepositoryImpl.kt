package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.sql.MoneyManagerDatabase
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class SettingsRepositoryImpl(
    database: MoneyManagerDatabase,
) : SettingsRepository {
    private val queries = database.settingsQueries

    override fun getDefaultCurrencyId(): Flow<CurrencyId?> =
        queries.selectDefaultCurrencyId()
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { it?.let(::CurrencyId) }

    override suspend fun setDefaultCurrencyId(currencyId: CurrencyId): Unit =
        withContext(Dispatchers.Default) {
            queries.upsertDefaultCurrency(currencyId.id)
        }

    override suspend fun clearDefaultCurrencyId(): Unit =
        withContext(Dispatchers.Default) {
            queries.clearDefaultCurrency()
        }
}
