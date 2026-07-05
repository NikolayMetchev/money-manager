package com.moneymanager.domain.repository

import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import kotlinx.coroutines.flow.Flow

interface CsvImportStrategyReadRepository {
    /**
     * Gets all import strategies, ordered by name.
     */
    fun getAllStrategies(): Flow<List<CsvImportStrategy>>

    /**
     * Gets a single strategy by ID.
     */
    fun getStrategyById(id: CsvImportStrategyId): Flow<CsvImportStrategy?>

    /**
     * Gets a single strategy by name.
     */
    fun getStrategyByName(name: String): Flow<CsvImportStrategy?>
}
