package com.moneymanager.domain.repository

import com.moneymanager.domain.model.RelationshipType
import com.moneymanager.domain.model.RelationshipTypeId
import kotlinx.coroutines.flow.Flow

interface RelationshipTypeReadRepository {
    fun getAll(): Flow<List<RelationshipType>>

    fun getById(id: RelationshipTypeId): Flow<RelationshipType?>

    fun getByName(name: String): Flow<RelationshipType?>
}
