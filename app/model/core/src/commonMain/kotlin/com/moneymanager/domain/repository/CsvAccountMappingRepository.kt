package com.moneymanager.domain.repository

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.csvstrategy.CsvAccountMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import kotlinx.coroutines.flow.Flow

interface CsvAccountMappingRepository {
    /**
     * Gets all mappings for a strategy, ordered by id (first match wins).
     */
    fun getMappingsForStrategy(strategyId: CsvImportStrategyId): Flow<List<CsvAccountMapping>>

    /**
     * Gets a single mapping by ID.
     */
    fun getMappingById(id: Long): Flow<CsvAccountMapping?>

    /**
     * Creates a new mapping.
     *
     * @param strategyId The strategy this mapping belongs to
     * @param columnName The CSV column to match against
     * @param valuePattern Regex pattern for matching column values
     * @param accountId Target account when pattern matches
     * @return The ID of the created mapping
     */
    suspend fun createMapping(
        strategyId: CsvImportStrategyId,
        columnName: String,
        valuePattern: Regex,
        accountId: AccountId,
    ): Long

    /**
     * Creates multiple mappings in a single database transaction.
     * Much faster than per-mapping [createMapping] calls.
     * The id field of each mapping is ignored; created/updated timestamps are honored.
     *
     * @param mappings The mappings to create
     */
    suspend fun createMappings(mappings: List<CsvAccountMapping>)

    /**
     * Updates an existing mapping.
     * The updatedAt timestamp will be set automatically.
     *
     * @param mapping The mapping with updated values
     */
    suspend fun updateMapping(mapping: CsvAccountMapping)

    /**
     * Deletes a mapping by ID.
     *
     * @param id The ID of the mapping to delete
     */
    suspend fun deleteMapping(id: Long)

    /**
     * Deletes all mappings for a strategy.
     *
     * @param strategyId The ID of the strategy
     */
    suspend fun deleteMappingsForStrategy(strategyId: CsvImportStrategyId)
}
