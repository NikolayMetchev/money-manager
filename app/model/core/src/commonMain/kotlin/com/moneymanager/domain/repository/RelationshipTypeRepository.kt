package com.moneymanager.domain.repository

import com.moneymanager.domain.model.RelationshipType
import com.moneymanager.domain.model.RelationshipTypeId
import kotlinx.coroutines.flow.Flow

interface RelationshipTypeRepository {
    fun getAll(): Flow<List<RelationshipType>>

    fun getById(id: RelationshipTypeId): Flow<RelationshipType?>

    fun getByName(name: String): Flow<RelationshipType?>

    /**
     * Gets or creates a relationship type by name.
     * If one with the given name already exists, returns its ID.
     * Otherwise, creates a new one and returns its ID.
     */
    suspend fun getOrCreate(name: String): RelationshipTypeId
}
