package com.moneymanager.domain.repository

import com.moneymanager.domain.model.ApiImportStrategyId
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import kotlinx.coroutines.flow.Flow

interface ApiImportStrategyReadRepository {
    /**
     * Gets all import strategies, ordered by name.
     */
    fun getAllStrategies(): Flow<List<ApiImportStrategy>>

    /**
     * Gets a single strategy by ID.
     */
    fun getStrategyById(id: ApiImportStrategyId): Flow<ApiImportStrategy?>

    /**
     * Gets a single strategy by name.
     */
    fun getStrategyByName(name: String): Flow<ApiImportStrategy?>
}
