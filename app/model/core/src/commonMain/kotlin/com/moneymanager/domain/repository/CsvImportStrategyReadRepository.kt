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

    /**
     * Finds a strategy that matches the given CSV column headings.
     * Matching is exact and order-independent.
     *
     * @param headings The column headings from the CSV file
     * @return The matching strategy, or null if no match found
     */
    suspend fun findMatchingStrategy(headings: Set<String>): CsvImportStrategy?
}
