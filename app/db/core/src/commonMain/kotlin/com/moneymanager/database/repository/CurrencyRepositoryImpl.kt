package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.mapper.CurrencyMapper
import com.moneymanager.database.sql.MoneyManagerDatabase
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.CurrencyScaleFactors
import com.moneymanager.domain.repository.CurrencyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class CurrencyRepositoryImpl(
    database: MoneyManagerDatabase,
) : CurrencyRepository {
    private val queries = database.currencyQueries

    override fun getAllCurrencies(): Flow<List<Currency>> =
        queries.selectAll()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map(CurrencyMapper::mapList)

    override fun getCurrencyById(id: CurrencyId): Flow<Currency?> =
        queries.selectById(id.id)
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { it?.let(CurrencyMapper::map) }

    override fun getCurrencyByCode(code: String): Flow<Currency?> =
        queries.selectByCode(code)
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { it?.let(CurrencyMapper::map) }

    override suspend fun upsertCurrencyByCode(
        code: String,
        name: String,
    ): CurrencyId =
        withContext(Dispatchers.Default) {
            queries.transactionWithResult {
                val existing = queries.selectByCode(code).executeAsOneOrNull()
                existing?.let { CurrencyId(it.id) }
                    ?: run {
                        val scaleFactor = CurrencyScaleFactors.getScaleFactor(code)
                        queries.insert(code, name, scaleFactor.toLong())
                        val newId = queries.lastInsertedId().executeAsOne()
                        CurrencyId(newId)
                    }
            }
        }

    override suspend fun updateCurrency(currency: Currency): Unit =
        withContext(Dispatchers.Default) {
            queries.update(
                code = currency.code,
                name = currency.name,
                scale_factor = currency.scaleFactor,
                id = currency.id.id,
            )
        }

    override suspend fun deleteCurrency(id: CurrencyId): Unit =
        withContext(Dispatchers.Default) {
            queries.delete(id.id)
        }
}
