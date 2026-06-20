package com.moneymanager.domain.repository

import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.PersonId

interface PersonAttributeWriteRepository : PersonAttributeReadRepository {
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
