package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.mapper.CurrencyMapper
import com.moneymanager.database.recordSource
import com.moneymanager.database.sql.MoneyManagerDatabase
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.CurrencyScaleFactors
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.repository.CurrencyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class CurrencyRepositoryImpl(
    database: MoneyManagerDatabase,
    private val deviceId: DeviceId,
) : CurrencyRepository {
    private val selectQueries = database.currencySelectQueries
    private val writeQueries = database.currencyWriteQueries
    private val database = database

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

    override suspend fun upsertCurrencyByCode(
        code: String,
        name: String,
        source: Source,
    ): CurrencyId =
        withContext(Dispatchers.Default) {
            writeQueries.transactionWithResult {
                val existing = selectQueries.selectByCode(code).executeAsOneOrNull()
                existing?.let { CurrencyId(it.id) }
                    ?: run {
                        val scaleFactor = CurrencyScaleFactors.getScaleFactor(code)
                        writeQueries.insert(code, name, scaleFactor.toLong())
                        val newId = writeQueries.lastInsertedId().executeAsOne()
                        // Only a freshly inserted currency records a source (the existing branch keeps its own).
                        database.recordSource(deviceId, EntityType.CURRENCY, newId, 1L, source)
                        CurrencyId(newId)
                    }
            }
        }

    override suspend fun updateCurrency(
        currency: Currency,
        source: Source,
    ): Unit =
        withContext(Dispatchers.Default) {
            writeQueries.transactionWithResult {
                writeQueries.update(
                    code = currency.code,
                    name = currency.name,
                    scale_factor = currency.scaleFactor,
                    id = currency.id.id,
                )
                val revision = selectQueries.selectById(currency.id.id).executeAsOne().revision_id
                database.recordSource(deviceId, EntityType.CURRENCY, currency.id.id, revision, source)
            }
        }

    override suspend fun deleteCurrency(id: CurrencyId): Unit =
        withContext(Dispatchers.Default) {
            writeQueries.delete(id.id)
        }
}
