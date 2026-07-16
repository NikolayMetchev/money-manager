@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.repository.write

import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.database.write.recordSource
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.CurrencyScaleFactors
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.domain.repository.write.CurrencyWriteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CurrencyWriteRepositoryImpl(
    private val database: MoneyManagerDatabaseWrapper,
    private val deviceId: DeviceId,
    reader: CurrencyReadRepository,
) : CurrencyWriteRepository,
    CurrencyReadRepository by reader {
    private val selectQueries = database.currencySelectQueries
    private val writeQueries = database.currencyWriteQueries
    private val assetWriteQueries = database.assetWriteQueries

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
                        // Allocate an id from the shared `asset` id space, then insert the currency with it.
                        assetWriteQueries.insert()
                        val newId = assetWriteQueries.lastInsertedId().executeAsOne()
                        writeQueries.insert(newId, code, name, scaleFactor)
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
