@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.importer

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.NewRelationship
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.repository.AccountAttributeRepository
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.PersonAccountOwnershipRepository
import com.moneymanager.domain.repository.PersonAttributeRepository
import com.moneymanager.domain.repository.PersonRepository
import com.moneymanager.domain.repository.TransactionRepository
import com.moneymanager.domain.repository.TransferUpdate
import com.moneymanager.importengineapi.AccountMatchKey
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.DedupePolicy
import com.moneymanager.importengineapi.forRow
import com.moneymanager.importengineapi.ImportAccountIntent
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportOwnershipIntent
import com.moneymanager.importengineapi.ImportProgress
import com.moneymanager.importengineapi.ImportResult
import com.moneymanager.importengineapi.ImportRowKey
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.LocalAccountKey
import com.moneymanager.importengineapi.LocalPersonKey
import com.moneymanager.importengineapi.PersonMatchKey
import com.moneymanager.importengineapi.RowOutcome
import kotlinx.coroutines.flow.first

/**
 * Database-backed [ImportEngine]. Takes a fully-built [ImportBatch] and performs the whole import:
 * creates (or reuses) accounts, people and ownerships, resolves transfer account references,
 * deduplicates against existing transfers, bulk-creates new transfers, applies updates for changed
 * duplicates, records the source of everything it writes, and returns counts plus per-row created ids.
 *
 * This is the only place imported entities/transfers are written to the database. CSV/QIF/API
 * importers build an [ImportBatch] and call [import]; all shared import logic lives here.
 */
class ImportEngineImpl(
    private val transactionRepository: TransactionRepository,
    private val accountRepository: AccountRepository,
    private val accountAttributeRepository: AccountAttributeRepository,
    private val personRepository: PersonRepository,
    private val personAttributeRepository: PersonAttributeRepository,
    private val ownershipRepository: PersonAccountOwnershipRepository,
) : ImportEngine {
    override suspend fun import(
        batch: ImportBatch,
        onProgress: (suspend (ImportProgress) -> Unit)?,
    ): ImportResult {
        onProgress?.invoke(ImportProgress("Resolving accounts"))
        val accountResolution = resolveAccounts(batch)

        onProgress?.invoke(ImportProgress("Resolving people"))
        val personKeyToId = resolvePeople(batch)

        onProgress?.invoke(ImportProgress("Linking ownerships"))
        val ownershipsCreated = createOwnerships(batch, personKeyToId, accountResolution.keyToId)

        // Resolve every transfer's account references to real ids.
        val resolvedTransfers =
            batch.transfers.map { transfer ->
                transfer.copy(
                    source = resolveRef(transfer.source, accountResolution.keyToId),
                    target = resolveRef(transfer.target, accountResolution.keyToId),
                )
            }

        onProgress?.invoke(ImportProgress("Detecting duplicates"))
        val existing = loadExisting(resolvedTransfers, batch)
        val classified = ImportDeduper(batch.dedupePolicy, existing).classify(resolvedTransfers)

        val toImport = classified.filter { it.status == ImportStatus.IMPORTED }
        val toUpdate = classified.filter { it.status == ImportStatus.UPDATED }
        val duplicates = classified.count { it.status == ImportStatus.DUPLICATE }
        val excluded = resolvedTransfers.count { it.excludedFromBalances }

        onProgress?.invoke(ImportProgress("Importing transactions"))
        val createdIds = writeTransfers(toImport, toUpdate, batch)

        val createdTransferIds = toImport.zip(createdIds).associate { (classified, id) -> classified.transfer.rowKey to id }

        // Build per-transfer outcomes in input order; IMPORTED rows pull their created id in turn.
        val createdIdByIndex = mutableMapOf<Int, TransferId>()
        var importPointer = 0
        classified.forEachIndexed { i, c ->
            if (c.status == ImportStatus.IMPORTED) {
                createdIds.getOrNull(importPointer++)?.let { createdIdByIndex[i] = it }
            }
        }
        val orderedRowOutcomes =
            classified.mapIndexed { i, c ->
                when (c.status) {
                    ImportStatus.IMPORTED -> RowOutcome(ImportStatus.IMPORTED, createdIdByIndex[i])
                    // For an in-batch duplicate, resolve to the earlier accepted transfer's created id.
                    else -> RowOutcome(c.status, c.existing ?: c.inBatchMatchIndex?.let { createdIdByIndex[it] })
                }
            }
        val rowOutcomes = classified.zip(orderedRowOutcomes).associate { (c, outcome) -> c.transfer.rowKey to outcome }

        return ImportResult(
            accountsCreated = accountResolution.created,
            peopleCreated = personKeyToId.createdCount,
            ownershipsCreated = ownershipsCreated,
            transfersImported = toImport.size,
            duplicates = duplicates,
            updated = toUpdate.size,
            excluded = excluded,
            createdTransferIds = createdTransferIds,
            rowOutcomes = rowOutcomes,
            orderedRowOutcomes = orderedRowOutcomes,
            createdAccountIds = accountResolution.keyToId,
        )
    }

    // region Accounts

    private class AccountResolution(
        val keyToId: Map<LocalAccountKey, AccountId>,
        val created: Int,
    )

    private suspend fun resolveAccounts(batch: ImportBatch): AccountResolution {
        if (batch.accountsToCreate.isEmpty()) return AccountResolution(emptyMap(), 0)

        val byName =
            accountRepository
                .getAllAccounts()
                .first()
                .associate { it.name to it.id }
                .toMutableMap()
        val byAttr = buildExistingAccountAttrIndex(batch.accountsToCreate)
        val byPersonalKey = mutableMapOf<String, AccountId>()

        val keyToId = mutableMapOf<LocalAccountKey, AccountId>()
        var created = 0
        for (intent in batch.accountsToCreate) {
            val existingId = matchAccount(intent.match, byName, byAttr, byPersonalKey)
            if (existingId != null) {
                keyToId[intent.key] = existingId
                continue
            }
            val newId = createAccount(intent, batch)
            created++
            keyToId[intent.key] = newId
            indexNewAccount(intent, newId, byName, byAttr, byPersonalKey)
        }
        return AccountResolution(keyToId, created)
    }

    private fun matchAccount(
        match: AccountMatchKey,
        byName: Map<String, AccountId>,
        byAttr: Map<Pair<AttributeTypeId, String>, AccountId>,
        byPersonalKey: Map<String, AccountId>,
    ): AccountId? =
        when (match) {
            is AccountMatchKey.ByName -> byName[match.name]
            is AccountMatchKey.ByExternalId -> byAttr[match.typeId to match.value]
            is AccountMatchKey.ByBuiltInType -> byAttr[match.typeId to match.value]
            is AccountMatchKey.ByPersonalCounterparty -> byPersonalKey[match.key]
            is AccountMatchKey.AlwaysCreate -> null
        }

    private suspend fun createAccount(
        intent: ImportAccountIntent,
        batch: ImportBatch,
    ): AccountId {
        val newId =
            accountRepository.createAccount(
                Account(
                    id = AccountId(0),
                    name = intent.name,
                    openingDate = intent.openingDate,
                    categoryId = intent.categoryId,
                ),
                batch.source,
            )
        intent.attributes.forEach { attr ->
            accountAttributeRepository.insertInCreationMode(newId, attr.typeId, attr.value)
        }
        return newId
    }

    private fun indexNewAccount(
        intent: ImportAccountIntent,
        id: AccountId,
        byName: MutableMap<String, AccountId>,
        byAttr: MutableMap<Pair<AttributeTypeId, String>, AccountId>,
        byPersonalKey: MutableMap<String, AccountId>,
    ) {
        byName.getOrPut(intent.name) { id }
        intent.attributes.forEach { attr -> byAttr.getOrPut(attr.typeId to attr.value) { id } }
        when (val match = intent.match) {
            is AccountMatchKey.ByPersonalCounterparty -> byPersonalKey.getOrPut(match.key) { id }
            else -> Unit
        }
    }

    /**
     * Builds an index of existing accounts by (attributeTypeId, value), but only for the attribute
     * types actually referenced by attribute-based match keys in this batch — so a name-only batch
     * (CSV/QIF) does not pay to scan every account's attributes.
     */
    private suspend fun buildExistingAccountAttrIndex(
        intents: List<ImportAccountIntent>,
    ): MutableMap<Pair<AttributeTypeId, String>, AccountId> {
        val relevantTypeIds =
            intents
                .mapNotNull { intent ->
                    when (val m = intent.match) {
                        is AccountMatchKey.ByExternalId -> m.typeId
                        is AccountMatchKey.ByBuiltInType -> m.typeId
                        else -> null
                    }
                }.toSet()
        if (relevantTypeIds.isEmpty()) return mutableMapOf()

        val index = mutableMapOf<Pair<AttributeTypeId, String>, AccountId>()
        for (account in accountRepository.getAllAccounts().first()) {
            val attrs = accountAttributeRepository.getByAccount(account.id).first()
            for (attr in attrs) {
                if (attr.attributeType.id in relevantTypeIds) {
                    index.getOrPut(attr.attributeType.id to attr.value) { account.id }
                }
            }
        }
        return index
    }

    // endregion

    // region People + ownership

    private class PersonResolution(
        val keyToId: Map<LocalPersonKey, PersonId>,
        val createdCount: Int,
    ) {
        operator fun get(key: LocalPersonKey): PersonId? = keyToId[key]
    }

    private suspend fun resolvePeople(batch: ImportBatch): PersonResolution {
        if (batch.peopleToCreate.isEmpty()) return PersonResolution(emptyMap(), 0)

        val existingPeople = personRepository.getAllPeople().first()
        val byNameKey = existingPeople.associate { normalizeNameKey(it.fullName) to it.id }.toMutableMap()
        val byAttr = buildExistingPersonAttrIndex(batch, existingPeople)

        val keyToId = mutableMapOf<LocalPersonKey, PersonId>()
        var created = 0
        for (intent in batch.peopleToCreate) {
            val existingId =
                when (val match = intent.match) {
                    is PersonMatchKey.ByExternalId -> byAttr[match.typeId to match.value]
                    is PersonMatchKey.ByNameKey -> byNameKey[match.nameKey]
                }
            if (existingId != null) {
                keyToId[intent.key] = existingId
                continue
            }
            val newId =
                personRepository.createPerson(
                    Person(
                        id = PersonId(0),
                        firstName = intent.firstName,
                        middleName = intent.middleName,
                        lastName = intent.lastName,
                    ),
                    batch.source,
                )
            intent.attributes.forEach { attr ->
                personAttributeRepository.insertInCreationMode(newId, attr.typeId, attr.value)
            }
            created++
            keyToId[intent.key] = newId
            byNameKey.getOrPut(
                normalizeNameKey(personFullName(intent.firstName, intent.middleName, intent.lastName)),
            ) { newId }
            when (val match = intent.match) {
                is PersonMatchKey.ByExternalId -> byAttr.getOrPut(match.typeId to match.value) { newId }
                is PersonMatchKey.ByNameKey -> Unit
            }
        }
        return PersonResolution(keyToId, created)
    }

    private suspend fun buildExistingPersonAttrIndex(
        batch: ImportBatch,
        existingPeople: List<Person>,
    ): MutableMap<Pair<AttributeTypeId, String>, PersonId> {
        val relevantTypeIds =
            batch.peopleToCreate
                .mapNotNull { (it.match as? PersonMatchKey.ByExternalId)?.typeId }
                .toSet()
        if (relevantTypeIds.isEmpty()) return mutableMapOf()

        val index = mutableMapOf<Pair<AttributeTypeId, String>, PersonId>()
        for (person in existingPeople) {
            val attrs = personAttributeRepository.getByPerson(person.id).first()
            for (attr in attrs) {
                if (attr.attributeType.id in relevantTypeIds) {
                    index.getOrPut(attr.attributeType.id to attr.value) { person.id }
                }
            }
        }
        return index
    }

    private suspend fun createOwnerships(
        batch: ImportBatch,
        personKeyToId: PersonResolution,
        accountKeyToId: Map<LocalAccountKey, AccountId>,
    ): Int {
        if (batch.ownerships.isEmpty()) return 0
        var created = 0
        // Track links created/known so duplicate intents in the same batch don't re-insert.
        val seen = mutableSetOf<Pair<PersonId, AccountId>>()
        for (intent in batch.ownerships) {
            if (createOwnershipIfNew(intent, batch, personKeyToId, accountKeyToId, seen)) created++
        }
        return created
    }

    private suspend fun createOwnershipIfNew(
        intent: ImportOwnershipIntent,
        batch: ImportBatch,
        personKeyToId: PersonResolution,
        accountKeyToId: Map<LocalAccountKey, AccountId>,
        seen: MutableSet<Pair<PersonId, AccountId>>,
    ): Boolean {
        val personId = personKeyToId[intent.personKey] ?: return false
        val accountId =
            when (val ref = intent.account) {
                is AccountRef.Existing -> ref.id
                is AccountRef.Local -> accountKeyToId[ref.key] ?: return false
            }
        if (!seen.add(personId to accountId)) return false
        val alreadyLinked =
            ownershipRepository.getOwnershipsByAccount(accountId).first().any { it.personId == personId }
        if (alreadyLinked) return false
        ownershipRepository.createOwnership(personId, accountId, batch.source)
        return true
    }

    // endregion

    // region Transfers

    private suspend fun loadExisting(
        transfers: List<ImportTransfer>,
        batch: ImportBatch,
    ): List<ExistingTransferInfo> {
        if (transfers.isEmpty()) return emptyList()

        val accountIds =
            transfers
                .flatMap { listOf(requireId(it.source), requireId(it.target)) }
                .toSet()

        val rawTransfers =
            when (batch.dedupePolicy) {
                is DedupePolicy.FuzzyAllFields -> {
                    val minTs = transfers.minOf { it.timestamp }
                    val maxTs = transfers.maxOf { it.timestamp }
                    accountIds
                        .flatMap {
                            transactionRepository.getTransactionsByAccountAndDateRange(it, minTs, maxTs).first()
                        }.distinctBy { it.id }
                }
                is DedupePolicy.UniqueIdentifier -> {
                    accountIds
                        .flatMap { transactionRepository.getTransactionsByAccount(it).first() }
                        .distinctBy { it.id }
                }
                is DedupePolicy.ApiMultiKey -> {
                    // Load full history for every account the batch touches (the own account may be the
                    // source or the target depending on transaction direction).
                    accountIds
                        .flatMap { transactionRepository.getTransactionsByAccount(it).first() }
                        .distinctBy { it.id }
                }
                is DedupePolicy.None -> emptyList()
            }

        val uniqueKeyExtractor = batch.uniqueKeyExtractor
        val apiIdExtractor = batch.apiIdExtractor
        return rawTransfers.map { transfer ->
            ExistingTransferInfo(
                transferId = transfer.id,
                transfer = transfer,
                attributes = transfer.attributes.associate { it.attributeType.id to it.value },
                uniqueKey = uniqueKeyExtractor?.extract(transfer).orEmpty(),
                apiId = apiIdExtractor?.extract(transfer),
            )
        }
    }

    /**
     * Creates the IMPORTED transfers and applies the UPDATED ones in a SINGLE database transaction
     * (one bulk-insert transaction). Returns the created ids, aligned to [toImport] order.
     */
    private suspend fun writeTransfers(
        toImport: List<Classified>,
        toUpdate: List<Classified>,
        batch: ImportBatch,
    ): List<TransferId> {
        if (toImport.isEmpty() && toUpdate.isEmpty()) return emptyList()

        // Assign negative temp ids so newAttributes/newRelationships can be keyed before real ids exist.
        // A main transfer carrying a fee expands into two transfers (main + fee) created in this same
        // batch; the fee is linked to the main via a `fee` relationship resolved by the repository's
        // in-batch id map. We track each main's position so only main ids are returned, aligned to toImport.
        val transfersToCreate = mutableListOf<Transfer>()
        val newAttributes = mutableMapOf<TransferId, List<NewAttribute>>()
        val newRelationships = mutableMapOf<TransferId, List<NewRelationship>>()
        val orderedRowKeys = mutableListOf<ImportRowKey>()
        val mainResultIndices = mutableListOf<Int>()
        var tempCounter = 0
        toImport.forEach { classified ->
            val t = classified.transfer
            val mainTempId = TransferId(-(++tempCounter).toLong())
            val fee = t.fee
            val feeTempId = if (fee != null) TransferId(-(++tempCounter).toLong()) else null
            val relationships =
                if (fee != null && feeTempId != null) {
                    t.relationships + NewRelationship(relatedTransferId = feeTempId, typeId = fee.relationshipTypeId)
                } else {
                    t.relationships
                }
            mainResultIndices += transfersToCreate.size
            transfersToCreate +=
                Transfer(
                    id = mainTempId,
                    timestamp = t.timestamp,
                    description = t.description,
                    sourceAccountId = requireId(t.source),
                    targetAccountId = requireId(t.target),
                    amount = t.amount,
                )
            if (t.attributes.isNotEmpty()) newAttributes[mainTempId] = t.attributes
            if (relationships.isNotEmpty()) newRelationships[mainTempId] = relationships
            orderedRowKeys += t.rowKey
            if (fee != null && feeTempId != null) {
                transfersToCreate +=
                    Transfer(
                        id = feeTempId,
                        timestamp = t.timestamp,
                        description = fee.description,
                        sourceAccountId = requireId(fee.source),
                        targetAccountId = requireId(fee.target),
                        amount = fee.amount,
                    )
                // The fee uses its own row key when provided (so the audit trail points at the fee's
                // source node), else the main's. Each transfer's source is derived from its own row key.
                orderedRowKeys += fee.rowKey ?: t.rowKey
            }
        }

        val updates = mutableListOf<TransferUpdate>()
        val orderedUpdateRowKeys = mutableListOf<ImportRowKey>()
        for (classified in toUpdate) {
            val existingId = classified.existing ?: continue
            val t = classified.transfer
            updates +=
                TransferUpdate(
                    transfer =
                        Transfer(
                            id = existingId,
                            timestamp = t.timestamp,
                            description = t.description,
                            sourceAccountId = requireId(t.source),
                            targetAccountId = requireId(t.target),
                            amount = t.amount,
                        ),
                    newAttributes = t.attributes,
                )
            orderedUpdateRowKeys += t.rowKey
        }

        val allCreatedIds =
            transactionRepository.importTransfers(
                transfers = transfersToCreate,
                newAttributes = newAttributes,
                newRelationships = newRelationships,
                sources = orderedRowKeys.map { batch.source.forRow(it) },
                updates = updates,
                updateSources = orderedUpdateRowKeys.map { batch.source.forRow(it) },
            )
        // Return only the main transfers' ids (fee transfers are interleaved), aligned to [toImport].
        val createdIds = mainResultIndices.map { allCreatedIds[it] }
        require(toImport.size == createdIds.size)
        return createdIds
    }

    // endregion

    private fun resolveRef(
        ref: AccountRef,
        keyToId: Map<LocalAccountKey, AccountId>,
    ): AccountRef =
        when (ref) {
            is AccountRef.Existing -> ref
            is AccountRef.Local ->
                AccountRef.Existing(
                    keyToId[ref.key] ?: error("Unresolved account reference: ${ref.key}"),
                )
        }

    private fun requireId(ref: AccountRef): AccountId =
        when (ref) {
            is AccountRef.Existing -> ref.id
            is AccountRef.Local -> error("Unresolved account reference: ${ref.key}")
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
}

/**
 * Normalises a full name into a stable matching key (trim, collapse internal whitespace, lower-case).
 * Shared so importers that produce [PersonMatchKey.ByNameKey] derive the same key the engine uses to
 * index existing people.
 */
fun normalizeNameKey(fullName: String): String = fullName.trim().replace(Regex("\\s+"), " ").lowercase()
