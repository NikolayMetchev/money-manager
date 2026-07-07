package com.moneymanager.domain.repository

import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.model.CryptoId
import kotlinx.coroutines.flow.Flow

interface CryptoReadRepository {
    fun getAllCryptoAssets(): Flow<List<CryptoAsset>>

    fun getCryptoAssetById(id: CryptoId): Flow<CryptoAsset?>

    fun getCryptoAssetByCode(code: String): Flow<CryptoAsset?>
}
