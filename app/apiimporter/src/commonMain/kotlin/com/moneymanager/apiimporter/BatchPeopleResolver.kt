package com.moneymanager.apiimporter

import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.Source
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.ImportOwnershipIntent
import com.moneymanager.importengineapi.ImportPersonIntent
import com.moneymanager.importengineapi.LocalPersonKey
import com.moneymanager.importengineapi.PersonMatchKey
import com.moneymanager.importengineapi.normalizeNameKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Pure, in-memory builder of [ImportPersonIntent]s + [ImportOwnershipIntent]s. Replaces the DB-writing
 * people/ownership machinery (`MutablePeopleIndex` / `resolveOrCreatePerson` / `importOwnersForAccount`):
 * the engine resolves/creates people (by external id or normalised name key), writes person attributes,
 * and creates ownerships, skipping already-linked / duplicate links. Only in-batch dedupe lives here, so
 * owners that map to the same person share one [LocalPersonKey] (and thus one created person).
 */
internal class BatchPeopleResolver {
    private val mutex = Mutex()
    private var keyCounter = 0
    private val intents = LinkedHashMap<LocalPersonKey, ImportPersonIntent>()
    private val ownerships = mutableListOf<ImportOwnershipIntent>()
    private val seenOwnerships = mutableSetOf<Pair<LocalPersonKey, AccountRef>>()

    // In-batch dedupe: a stable identity string (external id, bank key, or name key) -> the person key.
    private val byIdentity = mutableMapOf<String, LocalPersonKey>()

    suspend fun intents(): List<ImportPersonIntent> = mutex.withLock { intents.values.toList() }

    suspend fun ownershipIntents(): List<ImportOwnershipIntent> = mutex.withLock { ownerships.toList() }

    /**
     * Resolves (allocating if needed) the person for [owner] and records an ownership of [account].
     * Mirrors the old resolveOrCreatePerson: a usable display name is required (falling back to the bank
     * key or provider id); the provider's external id is matched/stored only when [externalIdAttributeTypeId]
     * is set. Returns silently when no usable identity exists.
     */
    suspend fun linkOwner(
        owner: ApiImportAccountOwner,
        account: AccountRef,
        externalIdAttributeTypeId: AttributeTypeId?,
        source: Source,
    ) = mutex.withLock {
        val personKey = resolvePerson(owner, externalIdAttributeTypeId, source) ?: return@withLock
        if (seenOwnerships.add(personKey to account)) {
            ownerships += ImportOwnershipIntent(personKey = personKey, account = account, source = source)
        }
    }

    /** Allocates the person intent for [owner] without recording an ownership (no linkable account). */
    suspend fun linkPersonOnly(
        owner: ApiImportAccountOwner,
        externalIdAttributeTypeId: AttributeTypeId?,
        source: Source,
    ) = mutex.withLock {
        resolvePerson(owner, externalIdAttributeTypeId, source)
        Unit
    }

    private fun resolvePerson(
        owner: ApiImportAccountOwner,
        externalIdAttributeTypeId: AttributeTypeId?,
        source: Source,
    ): LocalPersonKey? {
        val bankKey = owner.bankKey()
        val name = owner.preferredName?.trim().orEmpty()
        val rawExternalId = owner.userId.trim().ifBlank { null }
        val displayName = name.ifBlank { bankKey ?: rawExternalId ?: return null }
        val externalId = if (externalIdAttributeTypeId != null) rawExternalId else null

        // API people are matched ignoring middle names (a counterparty "Ada B Lovelace" is the same
        // person as "Ada Lovelace"): collapse to first+last before keying, so the ByNameKey the engine
        // matches against an existing person derives the same key. normalizeNameKey then trims+lowercases
        // to align with how the engine indexes existing people by their full name.
        val nameKey = normalizeNameKey(firstLastName(displayName))

        // Dedupe identity: prefer the provider external id, then the bank key, then the name key.
        val identityKey =
            when {
                externalId != null -> "ext:$externalId"
                bankKey != null -> "bank:$bankKey"
                else -> "name:$nameKey"
            }
        return byIdentity.getOrPut(identityKey) {
            val key = LocalPersonKey("person-${++keyCounter}")
            val nameParts = displayName.split(" ", limit = 2)
            val match =
                if (externalId != null && externalIdAttributeTypeId != null) {
                    // Fall back to name matching (and backfill the provider id) when no existing person
                    // carries this id, so the same person across providers isn't duplicated.
                    PersonMatchKey.ByExternalId(externalIdAttributeTypeId, externalId, nameKeyFallback = nameKey)
                } else {
                    PersonMatchKey.ByNameKey(nameKey)
                }
            intents[key] =
                ImportPersonIntent(
                    key = key,
                    match = match,
                    firstName = nameParts[0],
                    lastName = nameParts.getOrNull(1)?.ifBlank { null },
                    attributes =
                        if (externalId != null && externalIdAttributeTypeId != null) {
                            listOf(NewAttribute(typeId = externalIdAttributeTypeId, value = externalId))
                        } else {
                            emptyList()
                        },
                    source = source,
                )
            key
        }
    }
}

/**
 * The name-merge key for a counterparty account with no stable id or bank identity: first+last name,
 * lower-cased and whitespace-collapsed, so casing and middle-name variants of one person ("NIKOLAY
 * METCHEV", "Nikolay Ivanov Metchev") resolve to a single account. Mirrors the person name key.
 */
internal fun counterpartyNameKey(name: String): String = normalizeNameKey(firstLastName(name))

/** Collapses a full name to "first last" (dropping any middle parts), or the single word when there's one. */
internal fun firstLastName(fullName: String): String {
    val parts = fullName.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    return when {
        parts.isEmpty() -> fullName.trim()
        parts.size == 1 -> parts.single()
        else -> "${parts.first()} ${parts.last()}"
    }
}
