package com.moneymanager.domain.repository

import com.moneymanager.domain.model.Asset
import kotlinx.coroutines.flow.Flow

interface AssetRepository {
    fun getAllAssets(): Flow<List<Asset>>

    fun getAssetById(id: Long): Flow<Asset?>

    suspend fun upsertAssetByName(name: String): Long

    suspend fun updateAsset(asset: Asset)

    suspend fun deleteAsset(id: Long)
}
