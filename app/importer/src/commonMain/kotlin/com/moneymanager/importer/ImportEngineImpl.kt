@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.importer

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.NewRelationship
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.repository.AccountAttributeWriteRepository
import com.moneymanager.domain.repository.AccountWriteRepository
import com.moneymanager.domain.repository.CategoryWriteRepository
import com.moneymanager.domain.repository.PersonAccountOwnershipWriteRepository
import com.moneymanager.domain.repository.PersonAttributeWriteRepository
import com.moneymanager.domain.repository.PersonWriteRepository
import com.moneymanager.domain.repository.TransactionWriteRepository
import com.moneymanager.domain.repository.TransferUpdate
import com.moneymanager.importengineapi.AccountMatchKey
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.DedupePolicy
import com.moneymanager.importengineapi.EditGate
import com.moneymanager.importengineapi.ImportAccountIntent
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportOperation
import com.moneymanager.importengineapi.ImportOwnershipIntent
import com.moneymanager.importengineapi.ImportProgress
import com.moneymanager.importengineapi.ImportResult
import com.moneymanager.importengineapi.ImportRowKey
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.LocalAccountKey
import com.moneymanager.importengineapi.LocalCategoryKey
import com.moneymanager.importengineapi.LocalPersonKey
import com.moneymanager.importengineapi.PersonMatchKey
import com.moneymanager.importengineapi.RowOutcome
import com.moneymanager.importengineapi.WriteIntent
import com.moneymanager.importengineapi.bankKeyFromExternalId
import com.moneymanager.importengineapi.forRow
import com.moneymanager.importengineapi.normalizeNameKey
import com.moneymanager.importengineapi.personalCounterpartyKey
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
    private val transactionRepository: TransactionWriteRepository,
    private val accountRepository: AccountWriteRepository,
    private val accountAttributeRepository: AccountAttributeWriteRepository,
    private val personRepository: PersonWriteRepository,
    private val personAttributeRepository: PersonAttributeWriteRepository,
    private val ownershipRepository: PersonAccountOwnershipWriteRepository,
    private val categoryRepository: CategoryWriteRepository,
    private val editGate: EditGate = EditGate.AlwaysWritable,
) : ImportEngine {
    override suspend fun import(
        batch: ImportBatch,
        onProgress: (suspend (ImportProgress) -> Unit)?,
        batchSize: Int,
    ): ImportResult {
        editGate.ensureWritable()
        validate(batch)

        // ----- CREATE phase (first, so batch-local keys resolve for dependents) -----
        val createdCategoryIds = mutableMapOf<LocalCategoryKey, Long>()
        for (intent in batch.categories.creates()) {
            createdCategoryIds[intent.key] =
                categoryRepository.createCategory(
                    Category(name = requireNotNull(intent.name), parentId = intent.parentId),
                    intent.source,
                )
        }

        onProgress?.invoke(ImportProgress("Resolving accounts"))
        val accountResolution = resolveAccounts(batch.copy(accountsToCreate = batch.accountsToCreate.creates()))

        onProgress?.invoke(ImportProgress("Resolving people"))
        val personResolution = resolvePeople(batch.copy(peopleToCreate = batch.peopleToCreate.creates()))

        onProgress?.invoke(ImportProgress("Linking ownerships"))
        val ownershipsCreated =
            createOwnerships(
                batch.copy(ownerships = batch.ownerships.creates()),
                personResolution,
                accountResolution.keyToId,
            )

        val transfers = importTransferCreates(batch.transfers.creates(), batch, accountResolution.keyToId, batchSize, onProgress)

        // ----- UPDATE phase -----
        for (intent in batch.transfers.updates()) {
            transactionRepository.updateTransfer(
                transfer = intent.toUpdatedTransfer(),
                deletedAttributeIds = intent.deletedAttributeIds,
                updatedAttributes = intent.updatedAttributes,
                newAttributes = intent.attributes,
                transactionId = requireNotNull(intent.existingId),
                source = intent.source,
            )
        }
        for (intent in batch.accountsToCreate.updates()) {
            accountRepository.updateAccountWithAttributes(
                account = intent.account,
                accountId = requireNotNull(intent.existingId),
                deletedAttributeIds = intent.deletedAttributeIds,
                updatedAttributes = intent.updatedAttributes,
                newAttributes = intent.attributes,
                source = intent.source,
            )
        }
        for (intent in batch.categories.updates()) {
            categoryRepository.updateCategory(requireNotNull(intent.category), intent.source)
        }
        for (intent in batch.peopleToCreate.updates()) {
            personRepository.updatePersonWithAttributes(
                person = intent.person,
                personId = requireNotNull(intent.existingId),
                deletedAttributeIds = intent.deletedAttributeIds,
                updatedAttributes = intent.updatedAttributes,
                newAttributes = intent.attributes,
                source = intent.source,
            )
        }

        // ----- MERGE / UNMERGE (before deletes: a merge already removes the absorbed account) -----
        batch.accountMerges.forEach { accountRepository.mergeAccounts(it.deletedId, it.survivingId) }
        batch.accountUnmerges.forEach { accountRepository.unmergeAccount(it) }

        // ----- DELETE phase (dependents first) -----
        batch.ownerships.deletes().forEach { ownershipRepository.deleteOwnership(requireNotNull(it.existingId)) }
        batch.transfers.deletes().forEach { transactionRepository.deleteTransaction(requireNotNull(it.existingId).id) }
        batch.accountsToCreate.deletes().forEach { accountRepository.deleteAccount(requireNotNull(it.existingId)) }
        batch.categories.deletes().forEach { categoryRepository.deleteCategory(requireNotNull(it.existingId)) }
        batch.peopleToCreate.deletes().forEach { personRepository.deletePerson(requireNotNull(it.existingId)) }

        return ImportResult(
            accountsCreated = accountResolution.created,
            peopleCreated = personResolution.createdCount,
            ownershipsCreated = ownershipsCreated,
            transfersImported = transfers.imported,
            duplicates = transfers.duplicates,
            updated = transfers.updated,
            excluded = transfers.excluded,
            createdTransferIds = transfers.createdTransferIds,
            rowOutcomes = transfers.rowOutcomes,
            orderedRowOutcomes = transfers.orderedRowOutcomes,
            createdAccountIds = accountResolution.keyToId,
            createdCategoryIds = createdCategoryIds,
            createdPersonIds = personResolution.keyToId,
        )
    }

    // region Operation dispatch

    private fun <T : WriteIntent> List<T>.creates() = filter { it.operation == ImportOperation.CREATE }

    private fun <T : WriteIntent> List<T>.updates() = filter { it.operation == ImportOperation.UPDATE }

    private fun <T : WriteIntent> List<T>.deletes() = filter { it.operation == ImportOperation.DELETE }

    /** Fails loudly when an intent omits the fields its [ImportOperation] requires (nullable-by-design). */
    private fun validate(batch: ImportBatch) {
        batch.transfers.forEach {
            when (it.operation) {
                ImportOperation.CREATE ->
                    require(it.fromAccount != null && it.toAccount != null && it.timestamp != null && it.amount != null) {
                        "CREATE transfer requires fromAccount/toAccount/timestamp/amount"
                    }
                ImportOperation.UPDATE, ImportOperation.DELETE ->
                    require(it.existingId != null) { "${it.operation} transfer requires existingId" }
            }
        }
        batch.accountsToCreate.forEach {
            when (it.operation) {
                ImportOperation.CREATE ->
                    require(it.name != null && it.openingDate != null) { "CREATE account requires name/openingDate" }
                ImportOperation.UPDATE, ImportOperation.DELETE ->
                    require(it.existingId != null) { "${it.operation} account requires existingId" }
            }
        }
        batch.categories.forEach {
            when (it.operation) {
                ImportOperation.CREATE -> require(it.name != null) { "CREATE category requires name" }
                ImportOperation.UPDATE ->
                    require(it.existingId != null && it.category != null) { "UPDATE category requires existingId and category" }
                ImportOperation.DELETE -> require(it.existingId != null) { "DELETE category requires existingId" }
            }
        }
        batch.peopleToCreate.forEach {
            when (it.operation) {
                ImportOperation.CREATE -> require(it.firstName != null) { "CREATE person requires firstName" }
                ImportOperation.UPDATE, ImportOperation.DELETE ->
                    require(it.existingId != null) { "${it.operation} person requires existingId" }
            }
        }
        batch.ownerships.forEach {
            when (it.operation) {
                ImportOperation.CREATE ->
                    require((it.personKey != null || it.existingPersonId != null) && it.account != null) {
                        "CREATE ownership requires a person and an account"
                    }
                ImportOperation.DELETE -> require(it.existingId != null) { "DELETE ownership requires existingId" }
                ImportOperation.UPDATE -> error("Ownerships have no UPDATE operation")
            }
        }
    }

    /** Builds the [Transfer] for an UPDATE intent, or null for an attribute-only update. */
    private fun ImportTransfer.toUpdatedTransfer(): Transfer? =
        if (fromAccount == null && toAccount == null && amount == null && timestamp == null) {
            null
        } else {
            Transfer(
                id = requireNotNull(existingId),
                timestamp = requireNotNull(timestamp),
                description = description,
                sourceAccountId = requireId(fromAccount),
                targetAccountId = requireId(toAccount),
                amount = requireNotNull(amount),
            )
        }

    // endregion

    // region Transfer creates

    /** The transfer-related portion of an [ImportResult], produced by [importTransferCreates]. */
    private class TransferOutcome(
        val imported: Int,
        val duplicates: Int,
        val updated: Int,
        val excluded: Int,
        val createdTransferIds: Map<ImportRowKey, TransferId>,
        val rowOutcomes: Map<ImportRowKey, RowOutcome>,
        val orderedRowOutcomes: List<RowOutcome>,
    )

    /**
     * Runs the existing dedupe + write path over the CREATE transfers, resolving account references and
     * synthesising a [ImportRowKey.Manual] for manually-entered transfers that carry no source row.
     */
    private suspend fun importTransferCreates(
        creates: List<ImportTransfer>,
        batch: ImportBatch,
        accountKeyToId: Map<LocalAccountKey, AccountId>,
        batchSize: Int,
        onProgress: (suspend (ImportProgress) -> Unit)?,
    ): TransferOutcome {
        if (creates.isEmpty()) return TransferOutcome(0, 0, 0, 0, emptyMap(), emptyMap(), emptyList())

        var manualRowIndex = 0L
        val resolvedTransfers =
            creates.map { transfer ->
                val fee = transfer.fee
                transfer.copy(
                    rowKey = transfer.rowKey ?: ImportRowKey.Manual(manualRowIndex++),
                    fromAccount = resolveRef(requireNotNull(transfer.fromAccount), accountKeyToId),
                    toAccount = resolveRef(requireNotNull(transfer.toAccount), accountKeyToId),
                    fee =
                        fee?.copy(
                            source = resolveRef(fee.source, accountKeyToId),
                            target = resolveRef(fee.target, accountKeyToId),
                        ),
                )
            }

        onProgress?.invoke(ImportProgress("Detecting duplicates"))
        val existing = loadExisting(resolvedTransfers, batch)
        val classified = ImportDeduper(batch.dedupePolicy, existing).classify(resolvedTransfers)

        val toImport = classified.filter { it.status == ImportStatus.IMPORTED }
        val toUpdate = classified.filter { it.status == ImportStatus.UPDATED }
        val duplicates = classified.count { it.status == ImportStatus.DUPLICATE }
        val excluded = resolvedTransfers.count { it.excludedFromBalances }

        onProgress?.invoke(ImportProgress("Importing transactions", fraction = 0f, processed = 0, total = toImport.size))
        val createdIds = writeTransfers(toImport, toUpdate, batchSize, onProgress)

        val createdTransferIds = toImport.zip(createdIds).associate { (c, id) -> requireNotNull(c.transfer.rowKey) to id }

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
        val rowOutcomes =
            classified.zip(orderedRowOutcomes).associate { (c, outcome) -> requireNotNull(c.transfer.rowKey) to outcome }

        return TransferOutcome(
            imported = toImport.size,
            duplicates = duplicates,
            updated = toUpdate.size,
            excluded = excluded,
            createdTransferIds = createdTransferIds,
            rowOutcomes = rowOutcomes,
            orderedRowOutcomes = orderedRowOutcomes,
        )
    }

    // endregion

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
        val byPersonalKey = buildExistingPersonalKeyIndex(batch.accountsToCreate)

        val keyToId = mutableMapOf<LocalAccountKey, AccountId>()
        var created = 0
        for (intent in batch.accountsToCreate) {
            val match = matchAccount(intent, byName, byAttr, byPersonalKey)
            if (match != null) {
                // A bank-identity (adopted) match re-points the existing account onto this intent only for
                // own/source accounts; counterparties merge in silently keeping the existing account as-is.
                if (match.adopted && intent.adoptOnBankMatch) adoptAccount(intent, match.id, byName, byAttr)
                keyToId[intent.key] = match.id
                continue
            }
            val newId = createAccount(intent)
            created++
            keyToId[intent.key] = newId
            indexNewAccount(intent, newId, byName, byAttr, byPersonalKey)
        }
        return AccountResolution(keyToId, created)
    }

    /** A matched existing account; [adopted] is true for a bank-key fallback match that must be re-pointed. */
    private class AccountMatch(
        val id: AccountId,
        val adopted: Boolean,
    )

    private fun matchAccount(
        intent: ImportAccountIntent,
        byName: Map<String, AccountId>,
        byAttr: Map<Pair<AttributeTypeId, String>, AccountId>,
        byPersonalKey: Map<String, AccountId>,
    ): AccountMatch? {
        val primary =
            when (val match = intent.match) {
                is AccountMatchKey.ByName -> byName[match.name]
                is AccountMatchKey.ByExternalId -> byAttr[match.typeId to match.value]
                is AccountMatchKey.ByBuiltInType -> byAttr[match.typeId to match.value]
                is AccountMatchKey.ByPersonalCounterparty -> byPersonalKey[match.key]
                is AccountMatchKey.AlwaysCreate -> null
            }
        if (primary != null) return AccountMatch(primary, adopted = false)
        // Fallback: an intent that carries bank details (sort code + account number attributes) but didn't
        // match by its primary key adopts a pre-existing account for the same real bank account — e.g. a
        // source account adopting a counterparty another provider created, keeping imports order-independent.
        // The matched account is re-pointed (renamed + the intent's own attributes added) so this provider
        // resolves it by id next time, while its bank attributes keep the other provider matching it.
        return intentBankKey(intent)?.let { byPersonalKey[it] }?.let { AccountMatch(it, adopted = true) }
    }

    /**
     * Re-purposes a bank-key-matched account as this intent's account: renames it to [intent]'s name and
     * writes any of the intent's attributes it doesn't already have (e.g. this provider's external id).
     */
    private suspend fun adoptAccount(
        intent: ImportAccountIntent,
        id: AccountId,
        byName: MutableMap<String, AccountId>,
        byAttr: MutableMap<Pair<AttributeTypeId, String>, AccountId>,
    ) {
        val existing = accountRepository.getAccountById(id).first() ?: return
        val intentName = requireNotNull(intent.name)
        if (existing.name != intentName) {
            accountRepository.updateAccount(existing.copy(name = intentName), intent.source)
            byName.getOrPut(intentName) { id }
        }
        val currentAttrs = accountAttributeRepository.getByAccount(id).first()
        for (attr in intent.attributes) {
            val present = currentAttrs.any { it.attributeType.id == attr.typeId && it.value == attr.value }
            if (!present) {
                runCatching { accountAttributeRepository.insertInCreationMode(id, attr.typeId, attr.value) }
                byAttr.getOrPut(attr.typeId to attr.value) { id }
            }
        }
    }

    /** The personal-counterparty bank key from an intent's sort-code + account-number attributes, if both present. */
    private fun intentBankKey(intent: ImportAccountIntent): String? {
        val sortCodeTypeId = AttributeTypeId(WellKnownIds.ACCOUNT_SORT_CODE_ATTR_TYPE_ID)
        val accountNumberTypeId = AttributeTypeId(WellKnownIds.ACCOUNT_ACCOUNT_NUMBER_ATTR_TYPE_ID)
        val sortCode = intent.attributes.firstOrNull { it.typeId == sortCodeTypeId }?.value
        val accountNumber = intent.attributes.firstOrNull { it.typeId == accountNumberTypeId }?.value
        return if (sortCode != null && accountNumber != null) personalCounterpartyKey(sortCode, accountNumber) else null
    }

    private suspend fun createAccount(intent: ImportAccountIntent): AccountId {
        val newId =
            accountRepository.createAccount(
                Account(
                    id = AccountId(0),
                    name = requireNotNull(intent.name),
                    openingDate = requireNotNull(intent.openingDate),
                    categoryId = intent.categoryId,
                ),
                intent.source,
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
        byName.getOrPut(requireNotNull(intent.name)) { id }
        intent.attributes.forEach { attr -> byAttr.getOrPut(attr.typeId to attr.value) { id } }
        when (val match = intent.match) {
            is AccountMatchKey.ByPersonalCounterparty -> byPersonalKey.getOrPut(match.key) { id }
            else -> Unit
        }
        // Register the new account's bank identity (from its sort/account attributes) so a later intent in
        // this batch sharing those details merges into it instead of creating a duplicate.
        intentBankKey(intent)?.let { byPersonalKey.getOrPut(it) { id } }
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

    /**
     * Builds an index of existing accounts by their sort-code/account-number composite key, so an
     * incoming [AccountMatchKey.ByPersonalCounterparty] reconciles onto an account a *previous* import
     * already created for the same real bank account (cross-provider, order-independent). Only built when
     * the batch actually carries personal-counterparty match keys, so name-only batches pay nothing.
     */
    private suspend fun buildExistingPersonalKeyIndex(intents: List<ImportAccountIntent>): MutableMap<String, AccountId> {
        // Built when the batch carries any bank identity — a personal-counterparty match key, or an intent
        // (e.g. a source account) whose attributes include sort code + account number that may adopt an
        // existing account for the same real bank account.
        val hasPersonalKeys =
            intents.any { it.match is AccountMatchKey.ByPersonalCounterparty } ||
                intents.any { intentBankKey(it) != null }
        if (!hasPersonalKeys) return mutableMapOf()

        val sortCodeTypeId = AttributeTypeId(WellKnownIds.ACCOUNT_SORT_CODE_ATTR_TYPE_ID)
        val accountNumberTypeId = AttributeTypeId(WellKnownIds.ACCOUNT_ACCOUNT_NUMBER_ATTR_TYPE_ID)
        val externalIdTypeId = AttributeTypeId(WellKnownIds.ACCOUNT_EXTERNAL_ID_ATTR_TYPE_ID)

        val index = mutableMapOf<String, AccountId>()
        for (account in accountRepository.getAllAccounts().first()) {
            val attrs = accountAttributeRepository.getByAccount(account.id).first()
            val sortCode = attrs.firstOrNull { it.attributeType.id == sortCodeTypeId }?.value
            val accountNumber = attrs.firstOrNull { it.attributeType.id == accountNumberTypeId }?.value
            if (sortCode != null && accountNumber != null) {
                index.getOrPut(personalCounterpartyKey(sortCode, accountNumber)) { account.id }
            } else {
                // Fallback: an account whose only bank identity is a synthetic "bank:<sort>:<account>"
                // external-id (created before its sort/account were persisted as attributes). Don't
                // overwrite an attribute-backed entry — those are authoritative.
                val externalId = attrs.firstOrNull { it.attributeType.id == externalIdTypeId }?.value
                bankKeyFromExternalId(externalId)?.let { index.getOrPut(it) { account.id } }
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
            val match = intent.match
            val existingId =
                when (match) {
                    is PersonMatchKey.ByExternalId -> {
                        // Try the external id first, then (when given) fall back to a name match and
                        // backfill this provider's external id onto the matched person so future imports
                        // resolve them by id. Creation mode records the attribute at the person's existing
                        // revision rather than bumping a new one (no orphan revision).
                        byAttr[match.typeId to match.value]
                            ?: match.nameKeyFallback?.let { byNameKey[it] }?.also { matchedId ->
                                if (byAttr[match.typeId to match.value] == null) {
                                    runCatching {
                                        personAttributeRepository.insertInCreationMode(matchedId, match.typeId, match.value)
                                    }
                                    byAttr[match.typeId to match.value] = matchedId
                                }
                            }
                    }
                    is PersonMatchKey.ByNameKey -> byNameKey[match.nameKey]
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
            if (createOwnershipIfNew(intent, personKeyToId, accountKeyToId, seen)) created++
        }
        return created
    }

    private suspend fun createOwnershipIfNew(
        intent: ImportOwnershipIntent,
        personKeyToId: PersonResolution,
        accountKeyToId: Map<LocalAccountKey, AccountId>,
        seen: MutableSet<Pair<PersonId, AccountId>>,
    ): Boolean {
        val personId = intent.existingPersonId ?: intent.personKey?.let { personKeyToId[it] } ?: return false
        val accountId =
            when (val ref = intent.account) {
                is AccountRef.Existing -> ref.id
                is AccountRef.Local -> accountKeyToId[ref.key] ?: return false
                null -> return false
            }
        if (!seen.add(personId to accountId)) return false
        val alreadyLinked =
            ownershipRepository.getOwnershipsByAccount(accountId).first().any { it.personId == personId }
        if (alreadyLinked) return false
        ownershipRepository.createOwnership(personId, accountId, intent.source)
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
                .flatMap { listOf(requireId(it.fromAccount), requireId(it.toAccount)) }
                .toSet()

        val rawTransfers =
            when (batch.dedupePolicy) {
                is DedupePolicy.FuzzyAllFields -> {
                    val minTs = transfers.minOf { requireNotNull(it.timestamp) }
                    val maxTs = transfers.maxOf { requireNotNull(it.timestamp) }
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
     * Creates the IMPORTED transfers and applies the UPDATED ones. [batchSize] transfers are written per
     * database transaction: the default ([Int.MAX_VALUE]) writes everything (creates + updates) in one
     * transaction — the historical behaviour — while a smaller value chunks the creates across several
     * transactions and reports per-chunk progress, so a huge batch (e.g. sample-data generation) doesn't
     * freeze on one giant transaction. Updates ride with the final create chunk, so the single-chunk case
     * still applies creates and updates atomically together. Returns the created ids, aligned to
     * [toImport] order.
     */
    private suspend fun writeTransfers(
        toImport: List<Classified>,
        toUpdate: List<Classified>,
        batchSize: Int,
        onProgress: (suspend (ImportProgress) -> Unit)?,
    ): List<TransferId> {
        if (toImport.isEmpty() && toUpdate.isEmpty()) return emptyList()

        val effectiveBatchSize = batchSize.coerceAtLeast(1)
        // Fast-path the common single-transaction case (default batchSize) so it doesn't copy the whole
        // list into a one-element chunk.
        val chunks =
            when {
                toImport.isEmpty() -> emptyList()
                effectiveBatchSize >= toImport.size -> listOf(toImport)
                else -> toImport.chunked(effectiveBatchSize)
            }
        if (chunks.isEmpty()) {
            // No creates, only updates: apply them in their own transaction.
            val (updates, updateSources) = buildUpdates(toUpdate)
            transactionRepository.importTransfers(
                transfers = emptyList(),
                newAttributes = emptyMap(),
                newRelationships = emptyMap(),
                sources = emptyList(),
                updates = updates,
                updateSources = updateSources,
            )
            return emptyList()
        }

        val total = toImport.size
        val createdIds = mutableListOf<TransferId>()
        var written = 0
        chunks.forEachIndexed { index, chunk ->
            val payload = buildCreatePayload(chunk)
            // Updates ride with the final create chunk (one transaction in the common single-chunk case).
            val (updates, updateSources) =
                if (index == chunks.lastIndex) buildUpdates(toUpdate) else emptyList<TransferUpdate>() to emptyList()
            val allCreatedIds =
                transactionRepository.importTransfers(
                    transfers = payload.transfers,
                    newAttributes = payload.newAttributes,
                    newRelationships = payload.newRelationships,
                    sources = payload.sources,
                    updates = updates,
                    updateSources = updateSources,
                )
            // Keep only the main transfers' ids (fee transfers are interleaved), aligned to [toImport].
            createdIds += payload.mainResultIndices.map { allCreatedIds[it] }
            written += chunk.size
            onProgress?.invoke(
                ImportProgress(
                    detail = "Importing transactions",
                    fraction = written.toFloat() / total,
                    processed = written,
                    total = total,
                ),
            )
        }
        require(toImport.size == createdIds.size)
        return createdIds
    }

    /** The transfers (with fees expanded), attributes, relationships and sources to create for one chunk. */
    private class CreatePayload(
        val transfers: List<Transfer>,
        val newAttributes: Map<TransferId, List<NewAttribute>>,
        val newRelationships: Map<TransferId, List<NewRelationship>>,
        val sources: List<Source>,
        // Indices into [transfers] of each main transfer, so callers map results back to chunk order.
        val mainResultIndices: List<Int>,
    )

    private fun buildCreatePayload(chunk: List<Classified>): CreatePayload {
        // Assign negative temp ids so newAttributes/newRelationships can be keyed before real ids exist.
        // A main transfer carrying a fee expands into two transfers (main + fee) created in this same
        // chunk; the fee is linked to the main via a `fee` relationship resolved by the repository's
        // in-batch id map (which is per importTransfers call, so a main and its fee must stay together).
        val transfersToCreate = mutableListOf<Transfer>()
        val newAttributes = mutableMapOf<TransferId, List<NewAttribute>>()
        val newRelationships = mutableMapOf<TransferId, List<NewRelationship>>()
        val orderedSources = mutableListOf<Source>()
        val mainResultIndices = mutableListOf<Int>()
        var tempCounter = 0
        chunk.forEach { classified ->
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
            val rowKey = requireNotNull(t.rowKey)
            mainResultIndices += transfersToCreate.size
            transfersToCreate +=
                Transfer(
                    id = mainTempId,
                    timestamp = requireNotNull(t.timestamp),
                    description = t.description,
                    sourceAccountId = requireId(t.fromAccount),
                    targetAccountId = requireId(t.toAccount),
                    amount = requireNotNull(t.amount),
                )
            if (t.attributes.isNotEmpty()) newAttributes[mainTempId] = t.attributes
            if (relationships.isNotEmpty()) newRelationships[mainTempId] = relationships
            orderedSources += t.source.forRow(rowKey)
            if (fee != null && feeTempId != null) {
                transfersToCreate +=
                    Transfer(
                        id = feeTempId,
                        timestamp = requireNotNull(t.timestamp),
                        description = fee.description,
                        sourceAccountId = requireId(fee.source),
                        targetAccountId = requireId(fee.target),
                        amount = fee.amount,
                    )
                // The fee inherits the main transfer's provenance source, resolved against its own row
                // key when provided (so the audit trail points at the fee's source node), else the main's.
                orderedSources += t.source.forRow(fee.rowKey ?: rowKey)
            }
        }
        return CreatePayload(transfersToCreate, newAttributes, newRelationships, orderedSources, mainResultIndices)
    }

    private fun buildUpdates(toUpdate: List<Classified>): Pair<List<TransferUpdate>, List<Source>> {
        val updates = mutableListOf<TransferUpdate>()
        val orderedUpdateSources = mutableListOf<Source>()
        for (classified in toUpdate) {
            val existingId = classified.existing ?: continue
            val t = classified.transfer
            updates +=
                TransferUpdate(
                    transfer =
                        Transfer(
                            id = existingId,
                            timestamp = requireNotNull(t.timestamp),
                            description = t.description,
                            sourceAccountId = requireId(t.fromAccount),
                            targetAccountId = requireId(t.toAccount),
                            amount = requireNotNull(t.amount),
                        ),
                    newAttributes = t.attributes,
                )
            orderedUpdateSources += t.source.forRow(requireNotNull(t.rowKey))
        }
        return updates to orderedUpdateSources
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

    private fun requireId(ref: AccountRef?): AccountId =
        when (ref) {
            is AccountRef.Existing -> ref.id
            is AccountRef.Local -> error("Unresolved account reference: ${ref.key}")
            null -> error("Missing account reference")
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
