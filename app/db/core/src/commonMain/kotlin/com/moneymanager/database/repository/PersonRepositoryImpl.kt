package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.database.mapper.PersonMapper
import com.moneymanager.database.recordEntityProvenance
import com.moneymanager.domain.model.EntityProvenance
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.repository.PersonRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class PersonRepositoryImpl(
    private val database: MoneyManagerDatabaseWrapper,
) : PersonRepository {
    private val queries = database.personQueries
    private val attributeQueries = database.personAttributeQueries
    private val entitySourceQueries = database.entitySourceQueries

    override fun getAllPeople(): Flow<List<Person>> =
        queries
            .selectAll()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map(PersonMapper::mapList)

    override fun getPersonById(id: PersonId): Flow<Person?> =
        queries
            .selectById(id.id)
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { it?.let(PersonMapper::map) }

    override suspend fun createPerson(
        person: Person,
        provenance: EntityProvenance,
    ): PersonId =
        withContext(Dispatchers.Default) {
            val id =
                queries.transactionWithResult {
                    queries.insert(
                        first_name = person.firstName,
                        middle_name = person.middleName,
                        last_name = person.lastName,
                    )
                    val newId = queries.lastInsertRowId().executeAsOne()
                    entitySourceQueries.recordEntityProvenance(EntityType.PERSON, newId, 1L, provenance)
                    newId
                }
            PersonId(id)
        }

    override suspend fun updatePerson(person: Person): Unit =
        withContext(Dispatchers.Default) {
            queries.update(
                first_name = person.firstName,
                middle_name = person.middleName,
                last_name = person.lastName,
                id = person.id.id,
            )
        }

    override suspend fun updatePersonWithAttributes(
        person: Person?,
        personId: PersonId,
        deletedAttributeIds: Set<Long>,
        updatedAttributes: Map<Long, NewAttribute>,
        newAttributes: List<NewAttribute>,
    ): Long =
        withContext(Dispatchers.Default) {
            val effectivePersonId = person?.id ?: personId

            queries.transactionWithResult {
                updateEntityWithAttributes(
                    database = database,
                    hasEntityChanges = person != null,
                    deletedAttributeIds = deletedAttributeIds,
                    updatedAttributes = updatedAttributes,
                    newAttributes = newAttributes,
                    updateEntity = {
                        val personToUpdate = requireNotNull(person)
                        queries.update(
                            first_name = personToUpdate.firstName,
                            middle_name = personToUpdate.middleName,
                            last_name = personToUpdate.lastName,
                            id = personToUpdate.id.id,
                        )
                    },
                    bumpRevisionOnly = { queries.bumpRevisionOnly(effectivePersonId.id) },
                    selectRevision = { queries.selectRevisionById(effectivePersonId.id).executeAsOne() },
                    selectCurrentTypeId = { id ->
                        attributeQueries.selectById(id).executeAsOneOrNull()?.attribute_type_id
                    },
                    deleteById = { id -> attributeQueries.deleteById(id) },
                    insertAttribute = { attr ->
                        attributeQueries.insert(
                            person_id = effectivePersonId.id,
                            attribute_type_id = attr.typeId.id,
                            attribute_value = attr.value,
                        )
                    },
                    updateValue = { value, id -> attributeQueries.updateValue(value, id) },
                )
            }
        }

    override suspend fun deletePerson(id: PersonId): Unit =
        withContext(Dispatchers.Default) {
            queries.delete(id.id)
        }
}
