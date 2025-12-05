@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.mapper.CurrencyMapper
import com.moneymanager.database.sql.MoneyManagerDatabase
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.repository.CurrencyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

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
        queries.selectById(id.uuid.toString())
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
                existing?.let { CurrencyId(Uuid.parse(it.id)) }
                    ?: run {
                        val newId = Uuid.random()
                        queries.insert(newId.toString(), code, name)
                        CurrencyId(newId)
                    }
            }
        }

    override suspend fun updateCurrency(currency: Currency): Unit =
        withContext(Dispatchers.Default) {
            queries.update(
                code = currency.code,
                name = currency.name,
                id = currency.id.uuid.toString(),
            )
        }

    override suspend fun deleteCurrency(id: CurrencyId): Unit =
        withContext(Dispatchers.Default) {
            queries.delete(id.uuid.toString())
        }
}
