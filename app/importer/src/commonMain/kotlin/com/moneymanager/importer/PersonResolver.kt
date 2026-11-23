package com.moneymanager.importer

import com.moneymanager.coroutines.runCatchingCancellable
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.repository.write.PersonAccountOwnershipWriteRepository
import com.moneymanager.domain.repository.write.PersonAttributeWriteRepository
import com.moneymanager.domain.repository.write.PersonWriteRepository
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportOwnershipIntent
import com.moneymanager.importengineapi.LocalAccountKey
import com.moneymanager.importengineapi.LocalPersonKey
import com.moneymanager.importengineapi.PersonMatchKey
import com.moneymanager.importengineapi.normalizeNameKey
import kotlinx.coroutines.flow.first

/** Resolves a batch's person intents to person ids (matching existing people by attribute or name) and links ownerships. */
internal class PersonResolver(
    private val personRepository: PersonWriteRepository,
    private val personAttributeRepository: PersonAttributeWriteRepository,
    private val ownershipRepository: PersonAccountOwnershipWriteRepository,
) {
    class PersonResolution(
        val keyToId: Map<LocalPersonKey, PersonId>,
        val createdCount: Int,
    ) {
        operator fun get(key: LocalPersonKey): PersonId? = keyToId[key]
    }

    suspend fun resolvePeople(batch: ImportBatch): PersonResolution {
        if (batch.peopleToCreate.isEmpty()) return PersonResolution(emptyMap(), 0)

        val existingPeople = personRepository.getAllPeople().first()
        val byNameKey = existingPeople.associate { normalizeNameKey(it.fullName) to it.id }.toMutableMap()
        // A merged-away or otherwise renamed person is still found by its old names; a real name wins over an alias.
        val byAlias = personRepository.getAllAliases().first().associate { it.nameKey to it.personId }

        fun byName(key: String): PersonId? = byNameKey[key] ?: byAlias[key]
        val byAttr = buildExistingPersonAttrIndex(batch)

        val keyToId = mutableMapOf<LocalPersonKey, PersonId>()
        var created = 0
        for (intent in batch.peopleToCreate) {
            val match = intent.match
            val existingId =
                when (match) {
                    is PersonMatchKey.ByExternalId -> {
                        // Try the external id first, then (when given) fall back to a name match and
                        // backfill this provider's external id onto the matched person so future imports
                        // resolve them by id. Creation mode records the attribute at the person's existing
                        // revision rather than bumping a new one (no orphan revision).
                        byAttr[match.typeId to match.value]
                            ?: match.nameKeyFallback?.let(::byName)?.also { matchedId ->
                                if (byAttr[match.typeId to match.value] == null) {
                                    runCatchingCancellable {
                                        personAttributeRepository.insertInCreationMode(matchedId, match.typeId, match.value)
                                    }
                                    byAttr[match.typeId to match.value] = matchedId
                                }
                            }
                    }

                    is PersonMatchKey.ByNameKey -> {
                        byName(match.nameKey)
                    }
                }
            if (existingId != null) {
                keyToId[intent.key] = existingId
                continue
            }
            val firstName = requireNotNull(intent.firstName)
            val newId =
                personRepository.createPerson(
                    Person(
                        id = PersonId(0),
                        firstName = firstName,
                        middleName = intent.middleName,
                        lastName = intent.lastName,
                    ),
                    intent.source,
                )
            intent.attributes.forEach { attr ->
                personAttributeRepository.insertInCreationMode(newId, attr.typeId, attr.value)
            }
            created++
            keyToId[intent.key] = newId
            byNameKey.getOrPut(
                normalizeNameKey(personFullName(firstName, intent.middleName, intent.lastName)),
            ) { newId }
            when (match) {
                is PersonMatchKey.ByExternalId -> {
                    byAttr.getOrPut(match.typeId to match.value) { newId }
                    match.nameKeyFallback?.let { byNameKey.getOrPut(it) { newId } }
                }

                is PersonMatchKey.ByNameKey -> {}
            }
        }
        return PersonResolution(keyToId, created)
    }

    private suspend fun buildExistingPersonAttrIndex(batch: ImportBatch): MutableMap<Pair<AttributeTypeId, String>, PersonId> {
        val relevantTypeIds =
            batch.peopleToCreate
                .mapNotNull { (it.match as? PersonMatchKey.ByExternalId)?.typeId }
                .toSet()
        if (relevantTypeIds.isEmpty()) return mutableMapOf()

        val index = mutableMapOf<Pair<AttributeTypeId, String>, PersonId>()
        // One query over all person attributes rather than a per-person read: this used to be an N+1
        // over the whole person table during the "Resolving people" phase.
        for (attr in personAttributeRepository.getAll().first()) {
            if (attr.attributeType.id in relevantTypeIds) {
                index.getOrPut(attr.attributeType.id to attr.value) { attr.personId }
            }
        }
        return index
    }

    suspend fun createOwnerships(
        batch: ImportBatch,
        personKeyToId: PersonResolution,
        accountKeyToId: Map<LocalAccountKey, AccountId>,
    ) {
        if (batch.ownerships.isEmpty()) return
        // Track links created/known so duplicate intents in the same batch don't re-insert.
        val seen = mutableSetOf<Pair<PersonId, AccountId>>()
        for (intent in batch.ownerships) {
            createOwnershipIfNew(intent, personKeyToId, accountKeyToId, seen)
        }
    }

    private suspend fun createOwnershipIfNew(
        intent: ImportOwnershipIntent,
        personKeyToId: PersonResolution,
        accountKeyToId: Map<LocalAccountKey, AccountId>,
        seen: MutableSet<Pair<PersonId, AccountId>>,
    ) {
        val personId = intent.existingPersonId ?: intent.personKey?.let { personKeyToId[it] } ?: return
        val accountId =
            when (val ref = intent.account) {
                is AccountRef.Existing -> ref.id
                is AccountRef.Local -> accountKeyToId[ref.key] ?: return
                null -> return
            }
        if (!seen.add(personId to accountId)) return
        val alreadyLinked =
            ownershipRepository.getOwnershipsByAccount(accountId).first().any { it.personId == personId }
        if (alreadyLinked) return
        ownershipRepository.createOwnership(personId, accountId, intent.source)
    }
}

private fun personFullName(
    firstName: String,
    middleName: String?,
    lastName: String?,
): String =
    buildString {
        append(firstName)
        if (!middleName.isNullOrBlank()) append(" ").append(middleName)
        if (!lastName.isNullOrBlank()) append(" ").append(lastName)
    }
