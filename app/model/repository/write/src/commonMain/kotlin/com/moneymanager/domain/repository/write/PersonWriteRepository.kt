package com.moneymanager.domain.repository.write

import com.moneymanager.domain.model.MergeId
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.repository.PersonReadRepository

interface PersonWriteRepository : PersonReadRepository {
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

    /**
     * Merges [deletedPerson] into [survivingPerson]: the accounts it owns and its attributes move across, the
     * survivor gains the deleted person's name and aliases as aliases (so imports naming them find the survivor),
     * and the deleted person is removed. Reversible with [unmergePerson].
     */
    suspend fun mergePeople(
        deletedPerson: PersonId,
        survivingPerson: PersonId,
    ): MergeId

    suspend fun unmergePerson(mergeId: MergeId)

    /** Gives [person] the name [alias]; an alias another person holds moves to [person]. */
    suspend fun setAlias(
        alias: String,
        person: PersonId,
    )

    suspend fun removeAlias(alias: String)
}
