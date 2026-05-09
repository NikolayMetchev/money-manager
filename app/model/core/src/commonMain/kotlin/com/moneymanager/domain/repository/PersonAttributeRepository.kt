package com.moneymanager.domain.repository

import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.PersonAttribute
import com.moneymanager.domain.model.PersonId
import kotlinx.coroutines.flow.Flow

interface PersonAttributeRepository {
    /**
     * Gets all current attributes for a person.
     * Results are ordered by attribute type name.
     */
    fun getByPerson(personId: PersonId): Flow<List<PersonAttribute>>

    /**
     * Inserts a new attribute. This will trigger the attribute INSERT trigger
     * which bumps the person revision and records to audit.
     */
    suspend fun insert(
        personId: PersonId,
        attributeTypeId: AttributeTypeId,
        value: String,
    ): Long

    /**
     * Inserts a new attribute in creation mode: the attribute is recorded in the audit log
     * at the current revision without bumping the revision.
     */
    suspend fun insertInCreationMode(
        personId: PersonId,
        attributeTypeId: AttributeTypeId,
        value: String,
    ): Long

    /**
     * Updates an attribute's value. This will trigger the attribute UPDATE trigger
     * which bumps the person revision and records to audit.
     */
    suspend fun updateValue(
        id: Long,
        newValue: String,
    )

    /**
     * Deletes an attribute by ID. This will trigger the attribute DELETE trigger
     * which bumps the person revision and records to audit.
     */
    suspend fun delete(id: Long)
}
