package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.mapper.CurrencyMapper
import com.moneymanager.database.sql.read.MoneyManagerDatabase
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.repository.CurrencyReadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class CurrencyReadRepositoryImpl(
    database: MoneyManagerDatabase,
) : CurrencyReadRepository {
    private val selectQueries = database.currencySelectQueries

    override fun getAllCurrencies(): Flow<List<Currency>> =
        selectQueries
            .selectAll()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map(CurrencyMapper::mapList)

    override fun getCurrencyById(id: CurrencyId): Flow<Currency?> =
        selectQueries
            .selectById(id.id)
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { it?.let(CurrencyMapper::map) }

    override fun getCurrencyByCode(code: String): Flow<Currency?> =
        selectQueries
            .selectByCode(code)
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { it?.let(CurrencyMapper::map) }
}
