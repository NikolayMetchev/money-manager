package com.moneymanager.database.repository

import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.database.recordSource
import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.model.CryptoId
import com.moneymanager.domain.model.CryptoRegistry
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.repository.CryptoReadRepository
import com.moneymanager.domain.repository.CryptoWriteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CryptoWriteRepositoryImpl(
    private val database: MoneyManagerDatabaseWrapper,
    private val deviceId: DeviceId,
    reader: CryptoReadRepository,
) : CryptoWriteRepository,
    CryptoReadRepository by reader {
    private val selectQueries = database.cryptoSelectQueries
    private val writeQueries = database.cryptoWriteQueries
    private val assetWriteQueries = database.assetWriteQueries

    override suspend fun upsertCryptoByCode(
        code: String,
        name: String?,
        scaleFactor: Long?,
        source: Source,
    ): CryptoId =
        withContext(Dispatchers.Default) {
            writeQueries.transactionWithResult {
                val existing = selectQueries.selectByCode(code).executeAsOneOrNull()
                existing?.let { CryptoId(it.id) }
                    ?: run {
                        val scaleFactor = scaleFactor ?: CryptoRegistry.scaleFactorFor(code)
                        val displayName = name ?: CryptoRegistry.nameFor(code)
                        // Allocate an id from the shared `asset` id space, then insert the crypto with it.
                        assetWriteQueries.insert()
                        val newId = assetWriteQueries.lastInsertedId().executeAsOne()
                        writeQueries.insert(newId, code, displayName, scaleFactor)
                        database.recordSource(deviceId, EntityType.CRYPTO, newId, 1L, source)
                        CryptoId(newId)
                    }
            }
        }

    override suspend fun updateCryptoAsset(
        crypto: CryptoAsset,
        source: Source,
    ): Unit =
        withContext(Dispatchers.Default) {
            writeQueries.transactionWithResult {
                writeQueries.update(
                    code = crypto.code,
                    name = crypto.name,
                    scale_factor = crypto.scaleFactor,
                    id = crypto.id.id,
                )
                val revision = selectQueries.selectById(crypto.id.id).executeAsOne().revision_id
                database.recordSource(deviceId, EntityType.CRYPTO, crypto.id.id, revision, source)
            }
        }

    override suspend fun deleteCryptoAsset(id: CryptoId): Unit =
        withContext(Dispatchers.Default) {
            writeQueries.delete(id.id)
        }
}
