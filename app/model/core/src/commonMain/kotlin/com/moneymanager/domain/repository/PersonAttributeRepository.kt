package com.moneymanager.domain.repository

import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.PersonAttribute
import com.moneymanager.domain.model.PersonId
import kotlinx.coroutines.flow.Flow

interface PersonAttributeRepository {
    fun getByPerson(personId: PersonId): Flow<List<PersonAttribute>>

    suspend fun insert(
        personId: PersonId,
        attributeTypeId: AttributeTypeId,
        value: String,
    ): Long

    suspend fun insertInCreationMode(
        personId: PersonId,
        attributeTypeId: AttributeTypeId,
        value: String,
    ): Long

    suspend fun updateValue(
        id: Long,
        newValue: String,
    )

    suspend fun delete(id: Long)
}
