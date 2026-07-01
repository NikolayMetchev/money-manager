package com.moneymanager.domain.repository

import com.moneymanager.domain.model.accountmapping.AccountMapping
import kotlinx.coroutines.flow.Flow

interface AccountMappingReadRepository {
    /**
     * Gets all mappings, ordered by id (first match wins).
     */
    fun getAllMappings(): Flow<List<AccountMapping>>

    /**
     * Gets a single mapping by ID.
     */
    fun getMappingById(id: Long): Flow<AccountMapping?>
}
