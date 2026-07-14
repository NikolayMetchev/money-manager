package com.moneymanager.domain.repository.write

import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.repository.CsvImportStrategyReadRepository

interface CsvImportStrategyWriteRepository : CsvImportStrategyReadRepository {
    /**
     * Creates a new import strategy.
     *
     * @param strategy The strategy to create
     * @param source Provenance recorded for the new strategy revision
     * @return The ID of the created strategy
     */
    suspend fun createStrategy(
        strategy: CsvImportStrategy,
        source: Source,
    ): CsvImportStrategyId

    /**
     * Updates an existing import strategy.
     * The updatedAt timestamp will be set automatically.
     *
     * @param strategy The strategy with updated values
     * @param source Provenance recorded for the new strategy revision
     */
    suspend fun updateStrategy(
        strategy: CsvImportStrategy,
        source: Source,
    )

    /**
     * Deletes an import strategy.
     *
     * @param id The ID of the strategy to delete
     */
    suspend fun deleteStrategy(id: CsvImportStrategyId)
}
