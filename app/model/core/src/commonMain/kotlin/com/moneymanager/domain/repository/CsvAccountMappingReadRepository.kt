package com.moneymanager.domain.repository

import com.moneymanager.domain.model.csvstrategy.CsvAccountMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import kotlinx.coroutines.flow.Flow

interface CsvAccountMappingReadRepository {
    /**
     * Gets all mappings for a strategy, ordered by id (first match wins).
     */
    fun getMappingsForStrategy(strategyId: CsvImportStrategyId): Flow<List<CsvAccountMapping>>

    /**
     * Gets a single mapping by ID.
     */
    fun getMappingById(id: Long): Flow<CsvAccountMapping?>
}
