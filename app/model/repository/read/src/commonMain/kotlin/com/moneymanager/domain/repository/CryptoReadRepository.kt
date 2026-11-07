package com.moneymanager.domain.repository

import com.moneymanager.domain.model.AssetAlias
import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.model.CryptoId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

interface CryptoReadRepository {
    fun getAllCryptoAssets(): Flow<List<CryptoAsset>>

    fun getCryptoAssetById(id: CryptoId): Flow<CryptoAsset?>

    fun getCryptoAssetByCode(code: String): Flow<CryptoAsset?>

    /**
     * The database's own aliases, of currencies too, sorted. They live here rather than on a repository of their own
     * because this is the one every importer is already given, to create the crypto assets a file names.
     */
    fun getAssetAliases(): Flow<List<AssetAlias>>

    /** Crypto assets the system created: installed from the catalog's crypto list, rather than by an import or a person. */
    fun getSystemCreatedCryptoAssets(): Flow<List<CryptoAsset>>
}

/** The database's own aliases as `resolveAssetCode` reads them. */
suspend fun CryptoReadRepository.assetAliasMap(): Map<String, String> = getAssetAliases().first().associate { it.alias to it.assetCode }
