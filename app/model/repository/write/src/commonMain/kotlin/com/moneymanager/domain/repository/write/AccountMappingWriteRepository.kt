package com.moneymanager.domain.repository.write

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.accountmapping.AccountMapping
import com.moneymanager.domain.repository.AccountMappingReadRepository

interface AccountMappingWriteRepository : AccountMappingReadRepository {
    /**
     * Creates a new mapping.
     *
     * @param valuePattern Regex pattern for matching account source values
     * @param accountId Target account when pattern matches
     * @param strategyId The strategy to scope this mapping to, or null for a global mapping
     * @return The ID of the created mapping
     */
    suspend fun createMapping(
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
