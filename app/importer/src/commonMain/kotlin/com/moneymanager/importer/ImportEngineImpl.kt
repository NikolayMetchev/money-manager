@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.importer

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiResponseId
import com.moneymanager.domain.model.ApiResponseTransactionId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.CryptoId
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.MonzoCredentialId
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.NewRelationship
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.apistrategy.ApiImportStrategyId
import com.moneymanager.domain.model.csv.CsvImportId
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import com.moneymanager.domain.model.importdirectory.ImportDirectoryId
import com.moneymanager.domain.model.qif.QifImportId
import com.moneymanager.domain.repository.AccountAttributeWriteRepository
import com.moneymanager.domain.repository.AccountMappingWriteRepository
import com.moneymanager.domain.repository.AccountWriteRepository
import com.moneymanager.domain.repository.ApiImportStrategyWriteRepository
import com.moneymanager.domain.repository.ApiSessionWriteRepository
import com.moneymanager.domain.repository.AttributeTypeWriteRepository
import com.moneymanager.domain.repository.CategoryWriteRepository
import com.moneymanager.domain.repository.CryptoWriteRepository
import com.moneymanager.domain.repository.CsvImportStrategyWriteRepository
import com.moneymanager.domain.repository.CsvImportWriteRepository
import com.moneymanager.domain.repository.CurrencyWriteRepository
import com.moneymanager.domain.repository.ImportDirectoryWriteRepository
import com.moneymanager.domain.repository.PassThroughAccountWriteRepository
import com.moneymanager.domain.repository.PersonAccountOwnershipWriteRepository
import com.moneymanager.domain.repository.PersonAttributeWriteRepository
import com.moneymanager.domain.repository.PersonWriteRepository
import com.moneymanager.domain.repository.QifImportWriteRepository
import com.moneymanager.domain.repository.RelationshipTypeWriteRepository
import com.moneymanager.domain.repository.SettingsWriteRepository
import com.moneymanager.domain.repository.TradeWriteRepository
import com.moneymanager.domain.repository.TransactionWriteRepository
import com.moneymanager.domain.repository.TransferUpdate
import com.moneymanager.importengineapi.AccountMappingMutation
import com.moneymanager.importengineapi.AccountMatchKey
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.ApiSessionMutation
import com.moneymanager.importengineapi.ApiStrategyMutation
import com.moneymanager.importengineapi.CsvImportMutation
import com.moneymanager.importengineapi.CsvStrategyMutation
import com.moneymanager.importengineapi.DedupePolicy
import com.moneymanager.importengineapi.EditGate
import com.moneymanager.importengineapi.ImportAccountIntent
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportDirectoryMutation
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportOperation
import com.moneymanager.importengineapi.ImportOwnershipIntent
import com.moneymanager.importengineapi.ImportProgress
import com.moneymanager.importengineapi.ImportResult
import com.moneymanager.importengineapi.ImportRowKey
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.LocalAccountKey
import com.moneymanager.importengineapi.LocalCategoryKey
import com.moneymanager.importengineapi.LocalCryptoKey
import com.moneymanager.importengineapi.LocalCurrencyKey
import com.moneymanager.importengineapi.LocalPersonKey
import com.moneymanager.importengineapi.LocalTradeKey
import com.moneymanager.importengineapi.PassThroughMutation
import com.moneymanager.importengineapi.PersonMatchKey
import com.moneymanager.importengineapi.QifImportMutation
import com.moneymanager.importengineapi.RowOutcome
import com.moneymanager.importengineapi.WriteIntent
import com.moneymanager.importengineapi.bankKeyFromExternalId
import com.moneymanager.importengineapi.forRow
import com.moneymanager.importengineapi.normalizeNameKey
import com.moneymanager.importengineapi.personalCounterpartyKey
import kotlinx.coroutines.flow.first
import kotlin.time.Instant

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
    private val currencyRepository: CurrencyWriteRepository,
    private val cryptoRepository: CryptoWriteRepository,
    private val tradeRepository: TradeWriteRepository,
    private val attributeTypeRepository: AttributeTypeWriteRepository,
    private val relationshipTypeRepository: RelationshipTypeWriteRepository,
    private val csvImportStrategyRepository: CsvImportStrategyWriteRepository,
    private val apiImportStrategyRepository: ApiImportStrategyWriteRepository,
    private val accountMappingRepository: AccountMappingWriteRepository,
    private val csvImportRepository: CsvImportWriteRepository,
    private val qifImportRepository: QifImportWriteRepository,
    private val apiSessionRepository: ApiSessionWriteRepository,
    private val settingsRepository: SettingsWriteRepository,
    private val importDirectoryRepository: ImportDirectoryWriteRepository,
    private val passThroughAccountRepository: PassThroughAccountWriteRepository,
    private val editGate: EditGate = EditGate.AlwaysWritable,
) : ImportEngine {
    override suspend fun import(
        batch: ImportBatch,
        onProgress: (suspend (ImportProgress) -> Unit)?,
        batchSize: Int,
    ): ImportResult {
        editGate.ensureWritable()
        validate(batch)

        // ----- Lookup-table resolution (first: ids feed attributes/relationships built by callers) -----
        val attributeTypeIds = batch.attributeTypeNames.associateWith { attributeTypeRepository.getOrCreate(it) }
        val relationshipTypeIds = batch.relationshipTypeNames.associateWith { relationshipTypeRepository.getOrCreate(it) }

        // ----- Config / staging / session / settings / device mutations -----
        val config = applyConfigMutations(batch)

        // ----- CREATE phase (first, so batch-local keys resolve for dependents) -----
        val createdCurrencyIds = mutableMapOf<LocalCurrencyKey, CurrencyId>()
        for (intent in batch.currencies.creates()) {
            createdCurrencyIds[intent.key] =
                currencyRepository.upsertCurrencyByCode(
                    requireNotNull(intent.code),
                    requireNotNull(intent.name),
                    intent.source,
                )
        }
        val createdCryptoIds = mutableMapOf<LocalCryptoKey, CryptoId>()
        for (intent in batch.cryptoAssets.creates()) {
            createdCryptoIds[intent.key] =
                cryptoRepository.upsertCryptoByCode(requireNotNull(intent.code), intent.name, intent.scaleFactor, intent.source)
        }
        val createdTradeIds = mutableMapOf<LocalTradeKey, TradeId>()
        for (intent in batch.trades) {
            createdTradeIds[intent.key] =
                tradeRepository.createTrade(
                    timestamp = intent.timestamp,
                    description = intent.description,
                    fromAccountId = intent.fromAccountId,
                    fromAmount = intent.fromAmount,
                    toAccountId = intent.toAccountId,
                    toAmount = intent.toAmount,
                    source = intent.source,
                )
        }
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
        for (intent in batch.currencies.updates()) {
            currencyRepository.updateCurrency(requireNotNull(intent.currency), intent.source)
        }
        for (intent in batch.cryptoAssets.updates()) {
            cryptoRepository.updateCryptoAsset(requireNotNull(intent.crypto), intent.source)
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
        batch.currencies.deletes().forEach { currencyRepository.deleteCurrency(requireNotNull(it.existingId)) }
        batch.cryptoAssets.deletes().forEach { cryptoRepository.deleteCryptoAsset(requireNotNull(it.existingId)) }
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
            createdCurrencyIds = createdCurrencyIds,
            createdCryptoIds = createdCryptoIds,
            createdTradeIds = createdTradeIds,
            attributeTypeIds = attributeTypeIds,
            relationshipTypeIds = relationshipTypeIds,
            createdCsvStrategyIds = config.csvStrategyIds,
            createdApiStrategyIds = config.apiStrategyIds,
            createdAccountMappingIds = config.accountMappingIds,
            createdCsvImportIds = config.csvImportIds,
            createdQifImportIds = config.qifImportIds,
            createdImportDirectoryIds = config.importDirectoryIds,
            apiCredentialIds = config.apiCredentialIds,
            apiSessionIds = config.apiSessionIds,
            apiRequestIds = config.apiRequestIds,
            apiResponseIds = config.apiResponseIds,
            apiResponseTransactionIds = config.apiResponseTransactionIds,
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
        batch.currencies.forEach {
            when (it.operation) {
                ImportOperation.CREATE -> require(it.code != null && it.name != null) { "CREATE currency requires code/name" }
                ImportOperation.UPDATE ->
                    require(it.currency != null) { "UPDATE currency requires currency" }
                ImportOperation.DELETE -> require(it.existingId != null) { "DELETE currency requires existingId" }
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
                val passThrough = transfer.passThrough
                transfer.copy(
                    rowKey = transfer.rowKey ?: ImportRowKey.Manual(manualRowIndex++),
                    fromAccount = resolveRef(requireNotNull(transfer.fromAccount), accountKeyToId),
                    toAccount = resolveRef(requireNotNull(transfer.toAccount), accountKeyToId),
                    fee =
                        fee?.copy(
                            source = resolveRef(fee.source, accountKeyToId),
                            target = resolveRef(fee.target, accountKeyToId),
                        ),
                    passThrough =
                        passThrough?.copy(
                            conduits = passThrough.conduits.map { resolveRef(it, accountKeyToId) },
                            merchantTarget = resolveRef(passThrough.merchantTarget, accountKeyToId),
                        ),
                )
            }

        onProgress?.invoke(ImportProgress("Detecting duplicates"))
        val existing = loadExisting(resolvedTransfers, batch)
        val classified = ImportDeduper(batch.dedupePolicy, existing).classify(resolvedTransfers)

        val toImport = resolveReversalLinks(classified.filter { it.status == ImportStatus.IMPORTED })
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

    /**
     * Pairs each pass-through row's spend legs with the movements they reverse: per leg, the nearest
     * not-yet-reversed spend leg running in the OPPOSITE direction between the same pair of chain
     * accounts with the same amount, at or before the row's timestamp. An incoming refund thus links
     * each of its legs to the original charge's corresponding leg, a subsequent "refund reversal"
     * (outgoing again) links to the refund it undoes, and so on — each pair consumes one earlier leg (a
     * leg is reversed at most once, enforced against the DB via the query's NOT EXISTS and within this
     * batch via the claimed sets). Candidates come from the DB and from earlier rows in [toImport]; on
     * a timestamp tie the in-batch leg wins (it is the later write). Legs without a match are imported
     * unlinked.
     */
    private suspend fun resolveReversalLinks(toImport: List<Classified>): List<Classified> {
        if (toImport.none { it.transfer.passThrough != null }) return toImport
        val reversalTypeId = relationshipTypeRepository.getOrCreate(WellKnownIds.REVERSAL_RELATIONSHIP_TYPE_NAME)
        val claimedExisting = mutableSetOf<TransferId>()
        val claimedInBatch = mutableSetOf<LegKey>()
        val batchSpendLegs = mutableListOf<BatchSpendLeg>()
        return toImport.mapIndexed { index, classified ->
            val t = classified.transfer
            val passThrough = t.passThrough ?: return@mapIndexed classified
            // The chain's nodes: conduits (outermost first) then the merchant; leg i moves between
            // nodes[i] and nodes[i+1] (reversed for an incoming refund).
            val nodes = (passThrough.conduits + passThrough.merchantTarget).map { it.requireExistingId() }
            val timestamp = requireNotNull(t.timestamp)
            val amount = passThrough.amount

            val links = mutableMapOf<Int, ReversalLink>()
            for (legIndex in passThrough.conduits.indices) {
                val spendSource = if (passThrough.incoming) nodes[legIndex + 1] else nodes[legIndex]
                val spendTarget = if (passThrough.incoming) nodes[legIndex] else nodes[legIndex + 1]

                // The reversed candidate runs the opposite way: its source/target are this leg's swapped.
                val inBatchMatch =
                    batchSpendLegs
                        .filter { leg ->
                            LegKey(leg.toImportIndex, leg.legIndex) !in claimedInBatch &&
                                leg.source == spendTarget &&
                                leg.target == spendSource &&
                                leg.amount == amount &&
                                leg.timestamp <= timestamp
                        }.maxWithOrNull(compareBy({ it.timestamp }, { it.toImportIndex }))
                val existingMatch =
                    transactionRepository
                        .getUnreversedTransfersBetween(spendTarget, spendSource, amount, timestamp, reversalTypeId)
                        .firstOrNull { it.id !in claimedExisting }

                val link =
                    when {
                        inBatchMatch != null && (existingMatch == null || existingMatch.timestamp <= inBatchMatch.timestamp) -> {
                            claimedInBatch += LegKey(inBatchMatch.toImportIndex, inBatchMatch.legIndex)
                            ReversalLink(ReversalTarget.BatchRow(inBatchMatch.toImportIndex, inBatchMatch.legIndex), reversalTypeId)
                        }
                        existingMatch != null -> {
                            claimedExisting += existingMatch.id
                            ReversalLink(ReversalTarget.Existing(existingMatch.id), reversalTypeId)
                        }
                        else -> null
                    }
                if (link != null) links[legIndex] = link
                batchSpendLegs += BatchSpendLeg(index, legIndex, spendSource, spendTarget, amount, timestamp)
            }
            if (links.isEmpty()) classified else classified.copy(reversalLinks = links)
        }
    }

    /** Identifies one spend leg of one to-import row: (index into the to-import list, leg index). */
    private data class LegKey(
        val toImportIndex: Int,
        val legIndex: Int,
    )

    /** A pass-through spend leg produced by an earlier row in the current to-import list. */
    private class BatchSpendLeg(
        val toImportIndex: Int,
        val legIndex: Int,
        val source: AccountId,
        val target: AccountId,
        val amount: Money,
        val timestamp: Instant,
    )

    private fun AccountRef.requireExistingId(): AccountId =
        when (this) {
            is AccountRef.Existing -> id
            is AccountRef.Local -> error("Unresolved account reference: $key")
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
        // Spend-leg real ids from already-written chunks, keyed by (to-import index, leg index), so
        // later chunks can resolve reversal targets that point across a chunk boundary.
        val resolvedSpendLegIds = mutableMapOf<LegKey, TransferId>()
        var written = 0
        chunks.forEachIndexed { index, chunk ->
            val payload = buildCreatePayload(chunk, chunkStartIndex = index * effectiveBatchSize, resolvedSpendLegIds)
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
            payload.spendResultIndices.forEach { (legKey, resultIndex) ->
                resolvedSpendLegIds[legKey] = allCreatedIds[resultIndex]
            }
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
        // Pass-through spend legs: (to-import index, leg index) -> index into [transfers], so callers
        // can record the created spend-leg ids and later chunks can resolve cross-chunk reversal
        // targets to real ids.
        val spendResultIndices: Map<LegKey, Int>,
    )

    private fun buildCreatePayload(
        chunk: List<Classified>,
        chunkStartIndex: Int,
        // Real spend-leg ids created by earlier chunks, keyed by (to-import index, leg index), so a
        // reversal target that landed in a previous chunk (its temp id is no longer resolvable) still
        // links by real id.
        priorSpendLegIds: Map<LegKey, TransferId>,
    ): CreatePayload {
        // Assign negative temp ids so newAttributes/newRelationships can be keyed before real ids exist.
        // A main transfer carrying a fee expands into two transfers (main + fee) created in this same
        // chunk; the fee is linked to the main via a `fee` relationship resolved by the repository's
        // in-batch id map (which is per importTransfers call, so a main and its fee must stay together).
        val transfersToCreate = mutableListOf<Transfer>()
        val newAttributes = mutableMapOf<TransferId, List<NewAttribute>>()
        val newRelationships = mutableMapOf<TransferId, List<NewRelationship>>()
        val orderedSources = mutableListOf<Source>()
        val mainResultIndices = mutableListOf<Int>()
        // Spend-leg temp ids keyed by (to-import index, leg index), for reversal links targeting an
        // earlier row of this chunk; targets from earlier chunks resolve through [priorSpendLegIds]
        // instead (temp ids only resolve within one importTransfers call).
        val spendTempIdByLeg = mutableMapOf<LegKey, TransferId>()
        val spendResultIndices = mutableMapOf<LegKey, Int>()
        var tempCounter = 0
        chunk.forEachIndexed { chunkIndex, classified ->
            val t = classified.transfer
            val mainTempId = TransferId(-(++tempCounter).toLong())
            val fee = t.fee
            val feeTempId = if (fee != null) TransferId(-(++tempCounter).toLong()) else null
            val passThrough = t.passThrough
            // One spend leg per conduit in the chain (C1→C2, …, Cn→merchant).
            val spendTempIds = passThrough?.conduits?.map { TransferId(-(++tempCounter).toLong()) }
            val relationships =
                buildList {
                    addAll(t.relationships)
                    if (fee != null && feeTempId != null) {
                        add(NewRelationship(relatedTransferId = feeTempId, typeId = fee.relationshipTypeId))
                    }
                    if (passThrough != null && spendTempIds != null) {
                        add(NewRelationship(relatedTransferId = spendTempIds.first(), typeId = passThrough.relationshipTypeId))
                    }
                }
            val rowKey = requireNotNull(t.rowKey)

            // Appends one leg created in this chunk, sharing the main transfer's timestamp and provenance
            // (resolved against the leg's own row key when given, so a fee/spend leg's audit can point at
            // its own source node). Used for the main transfer and its expanded fee / pass-through spend
            // legs so the construction lives in one place.
            fun addLeg(
                tempId: TransferId,
                description: String,
                source: AccountRef,
                target: AccountRef,
                amount: Money,
                legRowKey: ImportRowKey?,
            ) {
                val sourceId = requireId(source)
                val targetId = requireId(target)
                // A transfer must move between two distinct accounts (enforced by a DB CHECK). The CSV path
                // collapses degenerate pass-through chains upstream (collapsePassThroughChain); this guards
                // the invariant for every producer with a clear error instead of an opaque SQLite failure.
                require(sourceId != targetId) {
                    "Refusing to create a self-transfer on account ${sourceId.id} for \"$description\""
                }
                transfersToCreate +=
                    Transfer(
                        id = tempId,
                        timestamp = requireNotNull(t.timestamp),
                        description = description,
                        sourceAccountId = sourceId,
                        targetAccountId = targetId,
                        amount = amount,
                    )
                orderedSources += t.source.forRow(legRowKey ?: rowKey)
            }
            mainResultIndices += transfersToCreate.size
            addLeg(
                mainTempId,
                t.description,
                requireNotNull(t.fromAccount),
                requireNotNull(t.toAccount),
                requireNotNull(t.amount),
                rowKey,
            )
            if (t.attributes.isNotEmpty()) newAttributes[mainTempId] = t.attributes
            if (relationships.isNotEmpty()) newRelationships[mainTempId] = relationships
            // The fee is a real movement out of the main transfer's account; counts in balances.
            if (fee != null && feeTempId != null) {
                addLeg(feeTempId, fee.description, fee.source, fee.target, fee.amount, fee.rowKey)
            }
            // The pass-through spend legs, one per adjacent pair of the chain (C1→C2, …, Cn→merchant),
            // same amount as the funding leg (the main transfer). Each movement links to the next leg
            // via the pass-through relationship (main→leg1→leg2→…), so every conduit nets to zero and
            // the spend is counted once. Outgoing charge: funding card -> C1, legs run towards the
            // merchant. Incoming refund/cancellation: funding C1 -> card, each leg reversed.
            if (passThrough != null && spendTempIds != null) {
                val nodes = passThrough.conduits + passThrough.merchantTarget
                for (legIndex in passThrough.conduits.indices) {
                    val spendTempId = spendTempIds[legIndex]
                    addLeg(
                        spendTempId,
                        passThrough.spendDescriptions[legIndex],
                        if (passThrough.incoming) nodes[legIndex + 1] else nodes[legIndex],
                        if (passThrough.incoming) nodes[legIndex] else nodes[legIndex + 1],
                        passThrough.amount,
                        passThrough.rowKey,
                    )
                    val legKey = LegKey(chunkStartIndex + chunkIndex, legIndex)
                    spendTempIdByLeg[legKey] = spendTempId
                    spendResultIndices[legKey] = transfersToCreate.lastIndex
                    val legRelationships = mutableListOf<NewRelationship>()
                    if (legIndex < spendTempIds.lastIndex) {
                        legRelationships += NewRelationship(spendTempIds[legIndex + 1], passThrough.relationshipTypeId)
                    }
                    // Reversal pairing: this spend leg (id1) reverses an earlier one (id2) — an existing
                    // transfer, an earlier row's spend leg in this chunk (temp id), or an earlier
                    // chunk's spend leg (already-created real id).
                    val reversalLink = classified.reversalLinks[legIndex]
                    if (reversalLink != null) {
                        val reversalTargetId =
                            when (val target = reversalLink.target) {
                                is ReversalTarget.Existing -> target.id
                                is ReversalTarget.BatchRow ->
                                    spendTempIdByLeg[LegKey(target.toImportIndex, target.legIndex)]
                                        ?: priorSpendLegIds[LegKey(target.toImportIndex, target.legIndex)]
                            }
                        if (reversalTargetId != null) {
                            legRelationships += NewRelationship(reversalTargetId, reversalLink.typeId)
                        }
                    }
                    if (legRelationships.isNotEmpty()) newRelationships[spendTempId] = legRelationships
                }
            }
        }
        return CreatePayload(transfersToCreate, newAttributes, newRelationships, orderedSources, mainResultIndices, spendResultIndices)
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

    // region Config / staging / session / settings / device mutations

    private class ConfigOutcome(
        val csvStrategyIds: Map<String, CsvImportStrategyId>,
        val apiStrategyIds: Map<String, ApiImportStrategyId>,
        val accountMappingIds: Map<String, Long>,
        val csvImportIds: Map<String, CsvImportId>,
        val qifImportIds: Map<String, QifImportId>,
        val importDirectoryIds: Map<String, ImportDirectoryId>,
        val apiCredentialIds: Map<String, MonzoCredentialId>,
        val apiSessionIds: Map<String, ApiSessionId>,
        val apiRequestIds: Map<String, ApiRequestId>,
        val apiResponseIds: Map<String, ApiResponseId>,
        val apiResponseTransactionIds: Map<String, ApiResponseTransactionId>,
    )

    // Fail fast rather than silently overwrite a generated id when two create mutations share a read-back
    // key in one batch — a duplicate key would otherwise drop the earlier id from the ImportResult map.
    private fun <K, V> MutableMap<K, V>.putUnique(
        key: K,
        value: V,
        label: String,
    ) {
        require(key !in this) { "Duplicate $label read-back key in ImportBatch: $key" }
        this[key] = value
    }

    private suspend fun applyConfigMutations(batch: ImportBatch): ConfigOutcome {
        for (m in batch.passThroughMutations) {
            when (m) {
                is PassThroughMutation.Create -> passThroughAccountRepository.create(m.account)
                is PassThroughMutation.Update -> passThroughAccountRepository.update(m.account)
                is PassThroughMutation.Delete -> passThroughAccountRepository.delete(m.id)
            }
        }
        val csvStrategyIds = mutableMapOf<String, CsvImportStrategyId>()
        for (m in batch.csvStrategyMutations) {
            when (m) {
                is CsvStrategyMutation.Create ->
                    csvStrategyIds.putUnique(m.key, csvImportStrategyRepository.createStrategy(m.strategy, m.source), "CsvStrategy")
                is CsvStrategyMutation.Update -> csvImportStrategyRepository.updateStrategy(m.strategy, m.source)
                is CsvStrategyMutation.Delete -> csvImportStrategyRepository.deleteStrategy(m.id)
            }
        }

        val apiStrategyIds = mutableMapOf<String, ApiImportStrategyId>()
        for (m in batch.apiStrategyMutations) {
            when (m) {
                is ApiStrategyMutation.Create ->
                    apiStrategyIds.putUnique(m.key, apiImportStrategyRepository.createStrategy(m.strategy, m.source), "ApiStrategy")
                is ApiStrategyMutation.Update -> apiImportStrategyRepository.updateStrategy(m.strategy, m.source)
                is ApiStrategyMutation.Delete -> apiImportStrategyRepository.deleteStrategy(m.id)
            }
        }

        val accountMappingIds = mutableMapOf<String, Long>()
        for (m in batch.accountMappingMutations) {
            when (m) {
                is AccountMappingMutation.Create ->
                    accountMappingIds.putUnique(
                        m.key,
                        accountMappingRepository.createMapping(m.valuePattern, m.accountId, m.strategyId),
                        "AccountMapping",
                    )
                is AccountMappingMutation.CreateBatch -> accountMappingRepository.createMappings(m.mappings)
                is AccountMappingMutation.Update -> accountMappingRepository.updateMapping(m.mapping)
                is AccountMappingMutation.Delete -> accountMappingRepository.deleteMapping(m.id)
            }
        }

        val csvImportIds = mutableMapOf<String, CsvImportId>()
        for (m in batch.csvImportMutations) {
            when (m) {
                is CsvImportMutation.Create ->
                    csvImportIds.putUnique(
                        m.key,
                        csvImportRepository.createImport(m.fileName, m.headers, m.rows, m.fileChecksum, m.fileLastModified),
                        "CsvImport",
                    )
                is CsvImportMutation.Delete -> csvImportRepository.deleteImport(m.id)
                is CsvImportMutation.UpdateRowTransferId -> csvImportRepository.updateRowTransferId(m.id, m.rowIndex, m.transferId)
                is CsvImportMutation.UpdateRowTransferIds -> csvImportRepository.updateRowTransferIdsBatch(m.id, m.rowTransferMap)
                is CsvImportMutation.UpdateRowStatus -> csvImportRepository.updateRowStatus(m.id, m.rowIndex, m.status, m.transferId)
                is CsvImportMutation.UpdateRowStatuses -> csvImportRepository.updateRowStatusesBatch(m.id, m.status, m.rowTransferMap)
                is CsvImportMutation.ResetRowStatuses -> csvImportRepository.resetRowStatuses(m.id, m.rowIndexes)
                is CsvImportMutation.SaveError -> csvImportRepository.saveError(m.id, m.rowIndex, m.errorMessage)
                is CsvImportMutation.ClearError -> csvImportRepository.clearError(m.id, m.rowIndex)
                is CsvImportMutation.ClearErrors -> csvImportRepository.clearErrors(m.id, m.rowIndexes)
                is CsvImportMutation.RecordApplication ->
                    csvImportRepository.recordImportApplication(
                        m.id,
                        m.strategyId,
                        m.strategyName,
                        m.appliedAt,
                    )
            }
        }

        val qifImportIds = mutableMapOf<String, QifImportId>()
        for (m in batch.qifImportMutations) {
            when (m) {
                is QifImportMutation.Create ->
                    qifImportIds.putUnique(
                        m.key,
                        qifImportRepository.createImport(m.fileName, m.records, m.accountType, m.fileChecksum, m.fileLastModified),
                        "QifImport",
                    )
                is QifImportMutation.Delete -> qifImportRepository.deleteImport(m.id)
                is QifImportMutation.UpdateRecordStatuses ->
                    qifImportRepository.updateRecordStatusesBatch(
                        m.id,
                        m.status,
                        m.recordTransferMap,
                    )
                is QifImportMutation.SaveError -> qifImportRepository.saveError(m.id, m.recordIndex, m.errorMessage)
                is QifImportMutation.ClearErrors -> qifImportRepository.clearErrors(m.id, m.recordIndexes)
                is QifImportMutation.RecordApplication ->
                    qifImportRepository.recordImportApplication(
                        m.id,
                        m.strategyId,
                        m.strategyName,
                        m.appliedAt,
                    )
            }
        }

        val importDirectoryIds = mutableMapOf<String, ImportDirectoryId>()
        for (m in batch.importDirectoryMutations) {
            when (m) {
                is ImportDirectoryMutation.Create ->
                    importDirectoryIds.putUnique(
                        m.key,
                        importDirectoryRepository.createDirectory(m.directory, m.source),
                        "ImportDirectory",
                    )
                is ImportDirectoryMutation.Update -> importDirectoryRepository.updateDirectory(m.directory, m.source)
                is ImportDirectoryMutation.Delete -> importDirectoryRepository.deleteDirectory(m.id)
                is ImportDirectoryMutation.RecordFileImported ->
                    importDirectoryRepository.recordFileImported(
                        m.directoryId,
                        m.fileRef,
                        m.fileName,
                        m.lastModified,
                        m.checksum,
                        m.csvImportId,
                        m.qifImportId,
                        m.importedAt,
                    )
            }
        }

        val apiCredentialIds = mutableMapOf<String, MonzoCredentialId>()
        val apiSessionIds = mutableMapOf<String, ApiSessionId>()
        val apiRequestIds = mutableMapOf<String, ApiRequestId>()
        val apiResponseIds = mutableMapOf<String, ApiResponseId>()
        val apiResponseTransactionIds = mutableMapOf<String, ApiResponseTransactionId>()
        for (m in batch.apiSessionMutations) {
            when (m) {
                is ApiSessionMutation.CreateCredential ->
                    apiCredentialIds.putUnique(
                        m.key,
                        apiSessionRepository.createCredential(m.token, m.createdAt, m.type, m.strategyId, m.privateKey, m.publicKey),
                        "ApiCredential",
                    )
                is ApiSessionMutation.UpdateCredentialStrategy ->
                    apiSessionRepository.updateCredentialStrategy(
                        m.credentialId,
                        m.strategyId,
                    )
                is ApiSessionMutation.UpdateCredentialKeys ->
                    apiSessionRepository.updateCredentialKeys(
                        m.credentialId,
                        m.privateKey,
                        m.publicKey,
                    )
                is ApiSessionMutation.CreateSession ->
                    apiSessionIds.putUnique(
                        m.key,
                        apiSessionRepository.createSession(m.token, m.deviceId, m.createdAt, expiresAt = null, m.type, m.credentialId),
                        "ApiSession",
                    )
                is ApiSessionMutation.InsertRequest ->
                    apiRequestIds.putUnique(
                        m.key,
                        apiSessionRepository.insertRequest(m.sessionId, m.method, m.url, m.headers),
                        "ApiRequest",
                    )
                is ApiSessionMutation.InsertResponse ->
                    apiResponseIds.putUnique(
                        m.key,
                        apiSessionRepository.insertResponse(m.requestId, m.sessionId, m.json),
                        "ApiResponse",
                    )
                is ApiSessionMutation.DeleteSession -> apiSessionRepository.deleteSession(m.id)
                is ApiSessionMutation.InsertResponseTransaction ->
                    apiResponseTransactionIds.putUnique(
                        m.key,
                        apiSessionRepository.insertResponseTransaction(m.responseId, m.jsonPath, m.state, m.transactionId, m.errorMessage),
                        "ApiResponseTransaction",
                    )
                is ApiSessionMutation.InsertResponseTransactions -> apiSessionRepository.insertResponseTransactions(m.transactions)
                is ApiSessionMutation.MarkSessionImported ->
                    apiSessionRepository.markSessionImported(m.id, m.revisionId, m.importedAt, m.importDurationMillis)
            }
        }

        batch.settings?.let { s ->
            s.defaultCurrencyId?.let { settingsRepository.setDefaultCurrencyId(it) }
            s.lastQifAccountId?.let { settingsRepository.setLastQifAccountId(it) }
        }

        return ConfigOutcome(
            csvStrategyIds = csvStrategyIds,
            apiStrategyIds = apiStrategyIds,
            accountMappingIds = accountMappingIds,
            csvImportIds = csvImportIds,
            qifImportIds = qifImportIds,
            importDirectoryIds = importDirectoryIds,
            apiCredentialIds = apiCredentialIds,
            apiSessionIds = apiSessionIds,
            apiRequestIds = apiRequestIds,
            apiResponseIds = apiResponseIds,
            apiResponseTransactionIds = apiResponseTransactionIds,
        )
    }

    // endregion
}
