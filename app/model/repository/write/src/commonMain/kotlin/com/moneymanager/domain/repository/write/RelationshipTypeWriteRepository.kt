package com.moneymanager.domain.repository.write

import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.repository.RelationshipTypeReadRepository

interface RelationshipTypeWriteRepository : RelationshipTypeReadRepository {
    /**
     * Gets or creates a relationship type by name.
     * If one with the given name already exists, returns its ID.
     * Otherwise, creates a new one and returns its ID.
     */
    suspend fun getOrCreate(name: String): RelationshipTypeId
}
