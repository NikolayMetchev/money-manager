package com.moneymanager.database.repository.write

import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.database.write.recordSource
import com.moneymanager.domain.model.AliasPublisher
import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.model.CryptoId
import com.moneymanager.domain.model.CryptoRegistry
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.repository.CryptoReadRepository
import com.moneymanager.domain.repository.write.CryptoWriteRepository
import com.moneymanager.domain.repository.write.NewCryptoAsset
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
    private val aliasSelectQueries = database.assetAliasSelectQueries
    private val aliasWriteQueries = database.assetAliasWriteQueries

    override suspend fun upsertCryptoByCode(
        code: String,
        name: String?,
        source: Source,
    ): CryptoId = upsertCryptosByCode(listOf(NewCryptoAsset(code, name, source))).single()

    override suspend fun upsertCryptosByCode(assets: List<NewCryptoAsset>): List<CryptoId> =
        withContext(Dispatchers.Default) {
            writeQueries.transactionWithResult {
                assets.map { asset ->
                    val existing = selectQueries.selectByCode(asset.code).awaitAsOneOrNull()
                    existing?.let { CryptoId(it.id) }
                        ?: run {
                            val displayName = asset.name ?: CryptoRegistry.nameFor(asset.code)
                            // Allocate an id from the shared `asset` id space, then insert the crypto with it.
                            assetWriteQueries.insert()
                            val newId = assetWriteQueries.lastInsertedId().awaitAsOne()
                            writeQueries.insert(newId, asset.code, displayName, CryptoAsset.CRYPTO_SCALE_FACTOR)
                            database.recordSource(deviceId, EntityType.CRYPTO, newId, 1L, asset.source)
                            CryptoId(newId)
                        }
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
                val revision = selectQueries.selectById(crypto.id.id).awaitAsOne().revision_id
                database.recordSource(deviceId, EntityType.CRYPTO, crypto.id.id, revision, source)
            }
        }

    override suspend fun deleteCryptoAsset(id: CryptoId): Unit =
        withContext(Dispatchers.Default) {
            writeQueries.delete(id.id)
        }

    override suspend fun setAssetAlias(
        alias: String,
        assetCode: String,
        publisher: AliasPublisher,
    ): Unit =
        withContext(Dispatchers.Default) {
            val aliasCode = alias.trim().uppercase()
            val code = assetCode.trim().uppercase()
            require(aliasCode.isNotEmpty()) { "An asset alias can't be blank" }
            require(aliasCode != code) { "'$aliasCode' can't be an alias of itself" }
            val assetId =
                requireNotNull(aliasSelectQueries.selectAssetIdByCode(code).awaitAsOneOrNull()) { "No asset has the code '$code'" }
            aliasWriteQueries.upsert(aliasCode, assetId, publisher.id)
        }

    override suspend fun removeAssetAlias(alias: String): Unit =
        withContext(Dispatchers.Default) {
            aliasWriteQueries.delete(alias.trim().uppercase())
        }
}
