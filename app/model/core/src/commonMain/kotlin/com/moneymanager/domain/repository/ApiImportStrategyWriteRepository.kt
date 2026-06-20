package com.moneymanager.domain.repository

import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.apistrategy.ApiImportStrategyId

interface ApiImportStrategyWriteRepository : ApiImportStrategyReadRepository {
    /**
     * Creates a new import strategy.
     */
    suspend fun createStrategy(
        strategy: ApiImportStrategy,
        source: Source,
    ): ApiImportStrategyId

    /**
     * Updates an existing import strategy.
     */
    suspend fun updateStrategy(
        strategy: ApiImportStrategy,
        source: Source,
    )

    /**
     * Deletes a strategy by ID.
     */
    suspend fun deleteStrategy(id: ApiImportStrategyId)
}
