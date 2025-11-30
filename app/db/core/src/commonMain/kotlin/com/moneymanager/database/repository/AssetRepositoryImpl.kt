package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.mapper.AssetMapper
import com.moneymanager.database.sql.MoneyManagerDatabase
import com.moneymanager.domain.model.Asset
import com.moneymanager.domain.repository.AssetRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class AssetRepositoryImpl(
    database: MoneyManagerDatabase,
) : AssetRepository {
    private val queries = database.assetQueries

    override fun getAllAssets(): Flow<List<Asset>> =
        queries.selectAll()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map(AssetMapper::mapList)

    override fun getAssetById(id: Long): Flow<Asset?> =
        queries.selectById(id)
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { it?.let(AssetMapper::map) }

    override suspend fun upsertAssetByName(name: String): Long =
        withContext(Dispatchers.Default) {
            val existing = queries.selectByName(name).executeAsOneOrNull()
            if (existing != null) {
                existing.id
            } else {
                queries.insert(name).await()
                val id = queries.lastInsertRowId().executeAsOne()
                println("DEBUG: upsertAssetByName('$name') - inserted, lastInsertRowId returned: $id")
                id
            }
        }

    override suspend fun updateAsset(asset: Asset): Unit =
        withContext(Dispatchers.Default) {
            queries.update(
                name = asset.name,
                id = asset.id,
            )
        }

    override suspend fun deleteAsset(id: Long): Unit =
        withContext(Dispatchers.Default) {
            queries.delete(id)
        }
}
