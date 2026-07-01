package com.moneymanager.domain.repository

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.accountmapping.AccountMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId

interface AccountMappingWriteRepository : AccountMappingReadRepository {
    /**
     * Creates a new mapping.
     *
     * @param columnName The CSV column to match against
     * @param valuePattern Regex pattern for matching column values
     * @param accountId Target account when pattern matches
     * @param strategyId The strategy to scope this mapping to, or null for a global mapping
     * @return The ID of the created mapping
     */
    suspend fun createMapping(
        columnName: String,
        valuePattern: Regex,
        accountId: AccountId,
        strategyId: CsvImportStrategyId? = null,
    ): Long

    /**
     * Creates multiple mappings in a single database transaction.
     * Much faster than per-mapping [createMapping] calls.
     * The id field of each mapping is ignored; created/updated timestamps are honored.
     *
     * @param mappings The mappings to create
     */
    suspend fun createMappings(mappings: List<AccountMapping>)

    /**
     * Updates an existing mapping.
     * The updatedAt timestamp will be set automatically.
     *
     * @param mapping The mapping with updated values
     */
    suspend fun updateMapping(mapping: AccountMapping)

    /**
     * Deletes a mapping by ID.
     *
     * @param id The ID of the mapping to delete
     */
    suspend fun deleteMapping(id: Long)
}
