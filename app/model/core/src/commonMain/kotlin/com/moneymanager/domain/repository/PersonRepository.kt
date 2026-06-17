package com.moneymanager.domain.repository

import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.Source
import kotlinx.coroutines.flow.Flow

interface PersonRepository {
    fun getAllPeople(): Flow<List<Person>>

    fun getPersonById(id: PersonId): Flow<Person?>

    suspend fun createPerson(
        person: Person,
        source: Source,
    ): PersonId

    suspend fun updatePerson(
        person: Person,
        source: Source,
    )

    /**
     * Atomically updates person fields and/or attributes, producing a single revision bump.
     * Pass [person] = null to skip updating person fields (attribute-only update).
     * Returns the final revision ID after all changes.
     */
    suspend fun updatePersonWithAttributes(
        person: Person?,
        personId: PersonId,
        deletedAttributeIds: Set<Long>,
        updatedAttributes: Map<Long, NewAttribute>,
        newAttributes: List<NewAttribute>,
        source: Source,
    ): Long

    suspend fun deletePerson(id: PersonId)
}
