package com.moneymanager.database.repository.write

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.database.write.recordSource
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.MergeId
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.personNameKey
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.domain.repository.write.PersonWriteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Clock

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
    private val aliasWriteQueries = database.personAliasWriteQueries
    private val aliasSelectQueries = database.personAliasSelectQueries
    private val mergeWriteQueries = database.personMergeWriteQueries
    private val mergeSelectQueries = database.personMergeSelectQueries

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
                    val newId = personWriteQueries.lastInsertRowId().awaitAsOne()
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
                val revision = personSelectQueries.selectRevisionById(person.id.id).awaitAsOne()
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
                        selectRevision = { personSelectQueries.selectRevisionById(effectivePersonId.id).awaitAsOne() },
                        selectCurrentSlot = { id ->
                            attributeSelectQueries.selectById(id).awaitAsOneOrNull()?.let {
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

    override suspend fun setAlias(
        alias: String,
        person: PersonId,
    ): Unit =
        withContext(Dispatchers.Default) {
            val trimmed = alias.trim()
            require(trimmed.isNotEmpty()) { "An alias cannot be blank" }
            aliasWriteQueries.upsert(personNameKey(trimmed), trimmed, person.id)
        }

    override suspend fun removeAlias(alias: String): Unit =
        withContext(Dispatchers.Default) {
            aliasWriteQueries.delete(personNameKey(alias))
        }

    override suspend fun mergePeople(
        deletedPerson: PersonId,
        survivingPerson: PersonId,
    ): MergeId =
        withContext(Dispatchers.Default) {
            require(deletedPerson != survivingPerson) { "Cannot merge a person into itself" }
            database.transactionWithResult {
                val deleted = personSelectQueries.selectById(deletedPerson.id).awaitAsOne()
                val survivor = personSelectQueries.selectById(survivingPerson.id).awaitAsOne()
                mergeWriteQueries.insertMerge(
                    merged_at = Clock.System.now().toEpochMilliseconds(),
                    surviving_person_id = survivingPerson.id,
                    deleted_person_id = deletedPerson.id,
                    deleted_first_name = deleted.first_name,
                    deleted_middle_name = deleted.middle_name,
                    deleted_last_name = deleted.last_name,
                    deleted_person_revision_id = deleted.revision_id,
                )
                val mergeId = mergeWriteQueries.lastInsertRowId().awaitAsOne()

                // Ownerships: the survivor may already own an account (UNIQUE person+account), so only the rest move.
                val survivorOwned =
                    personSelectQueries
                        .ownershipSelectByPerson(survivingPerson.id)
                        .awaitAsList()
                        .map { it.account_id }
                        .toSet()
                val deletedOwnerships = personSelectQueries.ownershipSelectByPerson(deletedPerson.id).awaitAsList()

                database.beginCreationMode()
                try {
                    deletedOwnerships.forEach { ownership ->
                        val grafted = ownership.account_id !in survivorOwned
                        mergeWriteQueries.insertOwnership(mergeId, ownership.account_id, if (grafted) 1L else 0L)
                        if (grafted) personWriteQueries.ownershipInsert(survivingPerson.id, ownership.account_id)
                    }

                    // Attributes carry the deleted person's external ids, so an API import still finds the survivor.
                    val survivorSlots =
                        attributeSelectQueries
                            .selectByPerson(survivingPerson.id)
                            .awaitAsList()
                            .map { it.attribute_type_id to it.group_key }
                            .toSet()
                    attributeSelectQueries.selectByPerson(deletedPerson.id).awaitAsList().forEach { attr ->
                        val grafted = (attr.attribute_type_id to attr.group_key) !in survivorSlots
                        mergeWriteQueries.insertAttribute(
                            mergeId,
                            attr.attribute_type_id,
                            attr.attribute_value,
                            attr.group_key,
                            if (grafted) 1L else 0L,
                        )
                        if (grafted) {
                            attributeWriteQueries.insert(
                                person_id = survivingPerson.id,
                                attribute_type_id = attr.attribute_type_id,
                                attribute_value = attr.attribute_value,
                                group_key = attr.group_key,
                            )
                        }
                    }
                } finally {
                    database.endCreationMode()
                }

                // Aliases: the deleted person's own move to the survivor, and its name becomes one too.
                aliasSelectQueries.selectByPerson(deletedPerson.id).awaitAsList().forEach { alias ->
                    aliasWriteQueries.moveToPerson(survivingPerson.id, alias.name_key)
                    mergeWriteQueries.insertAlias(mergeId, alias.name_key, MOVED_ALIAS)
                }
                val deletedName = personName(deleted.first_name, deleted.middle_name, deleted.last_name)
                val survivorName = personName(survivor.first_name, survivor.middle_name, survivor.last_name)
                val nameKey = personNameKey(deletedName)
                if (nameKey != personNameKey(survivorName) &&
                    aliasSelectQueries.selectPersonIdByNameKey(nameKey).awaitAsOneOrNull() == null
                ) {
                    aliasWriteQueries.insertIfAbsent(nameKey, deletedName, survivingPerson.id)
                    mergeWriteQueries.insertAlias(mergeId, nameKey, CREATED_ALIAS)
                }

                personWriteQueries.delete(deletedPerson.id)
                database.recordSource(deviceId, EntityType.PERSON, deletedPerson.id, deleted.revision_id, Source.Merge)
                MergeId(mergeId)
            }
        }

    override suspend fun unmergePerson(mergeId: MergeId): Unit =
        withContext(Dispatchers.Default) {
            database.transaction {
                val merge = mergeSelectQueries.selectById(mergeId.id).awaitAsOneOrNull() ?: return@transaction
                if (merge.reversed != 0L) return@transaction
                val survivor = merge.surviving_person_id
                val deletedId = merge.deleted_person_id
                val revision = merge.deleted_person_revision_id + 1
                personWriteQueries.insertWithId(
                    deletedId,
                    revision,
                    merge.deleted_first_name,
                    merge.deleted_middle_name,
                    merge.deleted_last_name,
                )
                database.recordSource(deviceId, EntityType.PERSON, deletedId, revision, Source.Unmerge)

                database.beginCreationMode()
                try {
                    mergeSelectQueries.selectOwnershipsForMerge(mergeId.id).awaitAsList().forEach { row ->
                        if (row.grafted != 0L) {
                            personSelectQueries
                                .ownershipSelectByPerson(survivor)
                                .awaitAsList()
                                .filter { it.account_id == row.account_id }
                                .forEach { personWriteQueries.ownershipDelete(it.id) }
                        }
                        // An account since deleted has no row to point at; the restored person simply doesn't own it.
                        if (accountExists(row.account_id)) personWriteQueries.ownershipInsert(deletedId, row.account_id)
                    }
                    mergeSelectQueries.selectAttributesForMerge(mergeId.id).awaitAsList().forEach { row ->
                        if (row.grafted != 0L) {
                            attributeSelectQueries
                                .selectByPerson(survivor)
                                .awaitAsList()
                                .filter {
                                    it.attribute_type_id == row.attribute_type_id &&
                                        it.group_key == row.group_key &&
                                        it.attribute_value == row.attribute_value
                                }.forEach { attributeWriteQueries.deleteById(it.id) }
                        }
                        attributeWriteQueries.insert(deletedId, row.attribute_type_id, row.attribute_value, row.group_key)
                    }
                } finally {
                    database.endCreationMode()
                }

                mergeSelectQueries.selectAliasesForMerge(mergeId.id).awaitAsList().forEach { row ->
                    if (row.kind ==
                        MOVED_ALIAS
                    ) {
                        aliasWriteQueries.moveToPerson(deletedId, row.name_key)
                    } else {
                        aliasWriteQueries.delete(row.name_key)
                    }
                }
                mergeWriteQueries.markReversed(mergeId.id)
            }
        }

    private suspend fun accountExists(accountId: Long): Boolean =
        database.accountSelectQueries.selectById(accountId).awaitAsOneOrNull() != null

    private fun personName(
        first: String,
        middle: String?,
        last: String?,
    ): String = listOfNotNull(first, middle?.takeIf { it.isNotBlank() }, last?.takeIf { it.isNotBlank() }).joinToString(" ")

    private companion object {
        const val MOVED_ALIAS = 0L
        const val CREATED_ALIAS = 1L
    }
}
