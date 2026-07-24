package com.moneymanager.database.repository.write

import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.database.write.recordSource
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.domain.repository.write.PersonWriteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PersonWriteRepositoryImpl(
    private val database: MoneyManagerDatabaseWrapper,
    private val deviceId: DeviceId,
    reader: PersonReadRepository,
) : PersonWriteRepository,
    PersonReadRepository by reader {
    private val personSelectQueries = database.personSelectQueries
    private val personWriteQueries = database.personWriteQueries
    private val attributeSelectQueries = database.personAttributeSelectQueries
    private val attributeWriteQueries = database.personAttributeWriteQueries

    override suspend fun createPerson(
        person: Person,
        source: Source,
    ): PersonId =
        withContext(Dispatchers.Default) {
            val id =
                personWriteQueries.transactionWithResult {
                    personWriteQueries.insert(
                        first_name = person.firstName,
                        middle_name = person.middleName,
                        last_name = person.lastName,
                    )
                    val newId = personWriteQueries.lastInsertRowId().executeAsOne()
                    database.recordSource(deviceId, EntityType.PERSON, newId, 1L, source)
                    newId
                }
            PersonId(id)
        }

    override suspend fun updatePerson(
        person: Person,
        source: Source,
    ): Unit =
        withContext(Dispatchers.Default) {
            personWriteQueries.transactionWithResult {
                personWriteQueries.update(
                    first_name = person.firstName,
                    middle_name = person.middleName,
                    last_name = person.lastName,
                    id = person.id.id,
                )
                val revision = personSelectQueries.selectRevisionById(person.id.id).executeAsOne()
                database.recordSource(deviceId, EntityType.PERSON, person.id.id, revision, source)
            }
        }

    override suspend fun updatePersonWithAttributes(
        person: Person?,
        personId: PersonId,
        deletedAttributeIds: Set<Long>,
        updatedAttributes: Map<Long, NewAttribute>,
        newAttributes: List<NewAttribute>,
        source: Source,
    ): Long =
        withContext(Dispatchers.Default) {
            val effectivePersonId = person?.id ?: personId

            personWriteQueries.transactionWithResult {
                val finalRevision =
                    updateEntityWithAttributes(
                        database = database,
                        hasEntityChanges = person != null,
                        deletedAttributeIds = deletedAttributeIds,
                        updatedAttributes = updatedAttributes,
                        newAttributes = newAttributes,
                        updateEntity = {
                            val personToUpdate = requireNotNull(person)
                            personWriteQueries.update(
                                first_name = personToUpdate.firstName,
                                middle_name = personToUpdate.middleName,
                                last_name = personToUpdate.lastName,
                                id = personToUpdate.id.id,
                            )
                        },
                        bumpRevisionOnly = { personWriteQueries.bumpRevisionOnly(effectivePersonId.id) },
                        selectRevision = { personSelectQueries.selectRevisionById(effectivePersonId.id).executeAsOne() },
                        selectCurrentSlot = { id ->
                            attributeSelectQueries.selectById(id).executeAsOneOrNull()?.let {
                                it.attribute_type_id to it.group_key
                            }
                        },
                        deleteById = { id -> attributeWriteQueries.deleteById(id) },
                        insertAttribute = { attr ->
                            attributeWriteQueries.insert(
                                person_id = effectivePersonId.id,
                                attribute_type_id = attr.typeId.id,
                                attribute_value = attr.value,
                                group_key = attr.groupKey,
                            )
                        },
                        updateValue = { value, id -> attributeWriteQueries.updateValue(value, id) },
                    )
                database.recordSource(
                    deviceId,
                    EntityType.PERSON,
                    effectivePersonId.id,
                    finalRevision,
                    source,
                )
                finalRevision
            }
        }

    override suspend fun deletePerson(id: PersonId): Unit =
        withContext(Dispatchers.Default) {
            personWriteQueries.delete(id.id)
        }
}
