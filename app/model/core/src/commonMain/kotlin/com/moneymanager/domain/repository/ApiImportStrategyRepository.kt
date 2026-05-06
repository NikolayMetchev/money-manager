package com.moneymanager.domain.repository

import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.apistrategy.ApiImportStrategyId
import kotlinx.coroutines.flow.Flow

interface ApiImportStrategyRepository {
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

    /**
     * Creates a new import strategy.
     */
    suspend fun createStrategy(strategy: ApiImportStrategy): ApiImportStrategyId

    /**
     * Updates an existing import strategy.
     */
    suspend fun updateStrategy(strategy: ApiImportStrategy)

    /**
     * Deletes a strategy by ID.
     */
    suspend fun deleteStrategy(id: ApiImportStrategyId)
}
