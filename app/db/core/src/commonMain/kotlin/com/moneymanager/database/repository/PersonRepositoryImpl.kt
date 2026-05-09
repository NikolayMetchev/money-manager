package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.database.mapper.PersonMapper
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

    override suspend fun createPerson(person: Person): PersonId =
        withContext(Dispatchers.Default) {
            val id =
                queries.transactionWithResult {
                    queries.insert(
                        first_name = person.firstName,
                        middle_name = person.middleName,
                        last_name = person.lastName,
                    )
                    queries.lastInsertRowId().executeAsOne()
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
            val hasAttributeChanges =
                deletedAttributeIds.isNotEmpty() ||
                    updatedAttributes.isNotEmpty() ||
                    newAttributes.isNotEmpty()
            val effectivePersonId = person?.id ?: personId

            queries.transactionWithResult {
                if (person != null) {
                    queries.update(
                        first_name = person.firstName,
                        middle_name = person.middleName,
                        last_name = person.lastName,
                        id = person.id.id,
                    )
                } else if (hasAttributeChanges) {
                    queries.bumpRevisionOnly(effectivePersonId.id)
                }

                if (hasAttributeChanges) {
                    database.beginCreationMode()
                    try {
                        deletedAttributeIds.forEach { id ->
                            attributeQueries.deleteById(id)
                        }

                        updatedAttributes.forEach { (id, attr) ->
                            val current = attributeQueries.selectById(id).executeAsOneOrNull()
                            if (current != null && current.attribute_type_id != attr.typeId.id) {
                                attributeQueries.deleteById(id)
                                attributeQueries.insert(
                                    person_id = effectivePersonId.id,
                                    attribute_type_id = attr.typeId.id,
                                    attribute_value = attr.value,
                                )
                            } else {
                                attributeQueries.updateValue(attr.value, id)
                            }
                        }

                        newAttributes.forEach { attr ->
                            attributeQueries.insert(
                                person_id = effectivePersonId.id,
                                attribute_type_id = attr.typeId.id,
                                attribute_value = attr.value,
                            )
                        }
                    } finally {
                        database.endCreationMode()
                    }
                }

                queries.selectRevisionById(effectivePersonId.id).executeAsOne()
            }
        }

    override suspend fun deletePerson(id: PersonId): Unit =
        withContext(Dispatchers.Default) {
            queries.delete(id.id)
        }
}
