package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.mapper.CryptoMapper
import com.moneymanager.database.sql.read.MoneyManagerDatabase
import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.model.CryptoId
import com.moneymanager.domain.repository.CryptoReadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class CryptoReadRepositoryImpl(
    database: MoneyManagerDatabase,
) : CryptoReadRepository {
    private val selectQueries = database.cryptoSelectQueries

    override fun getAllCryptoAssets(): Flow<List<CryptoAsset>> =
        selectQueries
            .selectAll()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map(CryptoMapper::mapList)

    override fun getCryptoAssetById(id: CryptoId): Flow<CryptoAsset?> =
        selectQueries
            .selectById(id.id)
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { it?.let(CryptoMapper::map) }

    override fun getCryptoAssetByCode(code: String): Flow<CryptoAsset?> =
        selectQueries
            .selectByCode(code)
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { it?.let(CryptoMapper::map) }
}
