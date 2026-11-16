package com.moneymanager.importer

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountAttribute
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.ApiCredentialId
import com.moneymanager.domain.model.ApiImportStrategyId
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiResponseId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.CryptoId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.ImportDirectoryId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.NewRelationship
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.Relationship
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.Trade
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.calendar.BusinessDayCalendar
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.observation.AttributeOverride
import com.moneymanager.domain.model.observation.ImportGroupId
import com.moneymanager.domain.model.observation.ImportGroupKey
import com.moneymanager.domain.model.observation.ImportRecordId
import com.moneymanager.domain.model.observation.ImportRecordKey
import com.moneymanager.domain.model.observation.MaterializedTransaction
import com.moneymanager.domain.model.observation.ObservationContent
import com.moneymanager.domain.model.observation.ObservationOwner
import com.moneymanager.domain.model.observation.TransferField
import com.moneymanager.domain.model.rules.SettlementWindow
import com.moneymanager.domain.model.rules.widen
import com.moneymanager.domain.model.withStatementAccount
import com.moneymanager.domain.repository.write.AccountAttributeWriteRepository
import com.moneymanager.domain.repository.write.AccountMappingWriteRepository
import com.moneymanager.domain.repository.write.AccountWriteRepository
import com.moneymanager.domain.repository.write.ApiImportStrategyWriteRepository
import com.moneymanager.domain.repository.write.ApiSessionWriteRepository
import com.moneymanager.domain.repository.write.AttributeTypeWriteRepository
import com.moneymanager.domain.repository.write.CategoryWriteRepository
import com.moneymanager.domain.repository.write.CryptoWriteRepository
import com.moneymanager.domain.repository.write.CsvImportStrategyWriteRepository
import com.moneymanager.domain.repository.write.CsvImportWriteRepository
import com.moneymanager.domain.repository.write.CurrencyWriteRepository
import com.moneymanager.domain.repository.write.ExchangeOrderWriteRepository
import com.moneymanager.domain.repository.write.HolidayCalendarWriteRepository
import com.moneymanager.domain.repository.write.ImportDirectoryWriteRepository
import com.moneymanager.domain.repository.write.ImportRecordWriteRepository
import com.moneymanager.domain.repository.write.NewCryptoAsset
import com.moneymanager.domain.repository.write.NewTrade
import com.moneymanager.domain.repository.write.ObservationWriteRepository
import com.moneymanager.domain.repository.write.PassThroughAccountWriteRepository
import com.moneymanager.domain.repository.write.PersonAccountOwnershipWriteRepository
import com.moneymanager.domain.repository.write.PersonAttributeWriteRepository
import com.moneymanager.domain.repository.write.PersonOverrideWriteRepository
import com.moneymanager.domain.repository.write.PersonWriteRepository
import com.moneymanager.domain.repository.write.ReconciliationLinkWriteRepository
import com.moneymanager.domain.repository.write.RelationshipTypeWriteRepository
import com.moneymanager.domain.repository.write.RelationshipWrite
import com.moneymanager.domain.repository.write.RelationshipWriteRepository
import com.moneymanager.domain.repository.write.SettingsWriteRepository
import com.moneymanager.domain.repository.write.TradeEdit
import com.moneymanager.domain.repository.write.TradeWriteRepository
import com.moneymanager.domain.repository.write.TransactionWriteRepository
import com.moneymanager.domain.repository.write.TransferEdit
import com.moneymanager.domain.repository.write.TransferUpdate
import com.moneymanager.importengineapi.AccountMappingMutation
import com.moneymanager.importengineapi.AccountMatchKey
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.ApiSessionMutation
import com.moneymanager.importengineapi.ApiStrategyMutation
import com.moneymanager.importengineapi.AssetAliasMutation
import com.moneymanager.importengineapi.AttributeSlot
import com.moneymanager.importengineapi.CsvImportMutation
import com.moneymanager.importengineapi.CsvStrategyMutation
import com.moneymanager.importengineapi.DedupePolicy
import com.moneymanager.importengineapi.EditGate
import com.moneymanager.importengineapi.ExpandedRow
import com.moneymanager.importengineapi.ExpandedTransfer
import com.moneymanager.importengineapi.ExpansionRow
import com.moneymanager.importengineapi.HolidayCalendarMutation
import com.moneymanager.importengineapi.ImportAccountIntent
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportDirectoryMutation
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportOperation
import com.moneymanager.importengineapi.ImportOwnershipIntent
import com.moneymanager.importengineapi.ImportResult
import com.moneymanager.importengineapi.ImportRowKey
import com.moneymanager.importengineapi.ImportTradeIntent
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.LegLink
import com.moneymanager.importengineapi.LegLinkTarget
import com.moneymanager.importengineapi.LocalAccountKey
import com.moneymanager.importengineapi.LocalCategoryKey
import com.moneymanager.importengineapi.LocalCryptoKey
import com.moneymanager.importengineapi.LocalCurrencyKey
import com.moneymanager.importengineapi.LocalPersonKey
import com.moneymanager.importengineapi.LocalTradeKey
import com.moneymanager.importengineapi.MaterializationReport
import com.moneymanager.importengineapi.MaterializationRequest
import com.moneymanager.importengineapi.Materializer.column
import com.moneymanager.importengineapi.ObservationExpander
import com.moneymanager.importengineapi.ObservationKey
import com.moneymanager.importengineapi.ObservationMutation
import com.moneymanager.importengineapi.ObservationOwnerKey
import com.moneymanager.importengineapi.ObservationSlot
import com.moneymanager.importengineapi.PassThroughMutation
import com.moneymanager.importengineapi.PersonMatchKey
import com.moneymanager.importengineapi.ReconciliationLinkMutation
import com.moneymanager.importengineapi.RelationshipMutation
import com.moneymanager.importengineapi.RowOutcome
import com.moneymanager.importengineapi.TradeAttributeMutation
import com.moneymanager.importengineapi.TradeDedupePolicy
import com.moneymanager.importengineapi.WriteIntent
import com.moneymanager.importengineapi.bankKeyFromExternalId
import com.moneymanager.importengineapi.bankKeysFrom
import com.moneymanager.importengineapi.forRow
import com.moneymanager.importengineapi.normalizeNameKey
import com.moneymanager.importengineapi.observationContent
import com.moneymanager.importengineapi.observationKey
import com.moneymanager.importengineapi.observationOwner
import com.moneymanager.tasks.Progress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Transfers and trades are deleted this many per database transaction, with a progress update after each:
 * a commit per row waits on the disk every time (synchronous=FULL), which made a re-import's delete phase
 * ~8x slower than its deletes.
 */
private const val DELETE_CHUNK_ROWS = 500

/** The content hash of an API item's import record until the API sync (#1429) hashes what each item says. */
private const val RECORD_UNHASHED = "unhashed"

/** Upper bound on the legs walked along one pass-through chain; real chains are a few hops (card → Curve → PayPal → merchant). */
private const val MAX_PASS_THROUGH_CHAIN_LEGS = 16

/**
 * Attribute types that tie an account to a specific real bank account/counterparty identity. An
 * existing account carrying none of these is "unclaimed" — see [ImportEngineImpl.matchAccount].
 */
private val FEE_ACCOUNT_FOR_TYPE = AttributeTypeId(WellKnownIds.ACCOUNT_FEE_ACCOUNT_FOR_ATTR_TYPE_ID)

private val IDENTITY_ATTR_TYPE_IDS =
    setOf(
        AttributeTypeId(WellKnownIds.ACCOUNT_EXTERNAL_ID_ATTR_TYPE_ID),
        AttributeTypeId(WellKnownIds.ACCOUNT_SORT_CODE_ATTR_TYPE_ID),
        AttributeTypeId(WellKnownIds.ACCOUNT_ACCOUNT_NUMBER_ATTR_TYPE_ID),
        AttributeTypeId(WellKnownIds.BUILT_IN_COUNTERPARTY_TYPE_ATTR_TYPE_ID),
    )

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
    private val exchangeOrderRepository: ExchangeOrderWriteRepository,
    private val attributeTypeRepository: AttributeTypeWriteRepository,
    private val relationshipTypeRepository: RelationshipTypeWriteRepository,
    private val csvImportStrategyRepository: CsvImportStrategyWriteRepository,
    private val apiImportStrategyRepository: ApiImportStrategyWriteRepository,
    private val accountMappingRepository: AccountMappingWriteRepository,
    private val csvImportRepository: CsvImportWriteRepository,
    private val apiSessionRepository: ApiSessionWriteRepository,
    private val settingsRepository: SettingsWriteRepository,
    private val importDirectoryRepository: ImportDirectoryWriteRepository,
    private val passThroughAccountRepository: PassThroughAccountWriteRepository,
    /**
     * The locations business-day reconcile windows count on. Optional like [relationshipRepository]: without
     * it every account is on a weekends-only calendar. DI supplies it.
     */
    private val holidayCalendarRepository: HolidayCalendarWriteRepository? = null,
    /**
     * Read-only view of transfer relationships, used to see which existing legs a `reconciled` link
     * already claims (see [ImportDeduper.claimedReconcileTargets]). Optional so the hand-built engines in
     * tests need not wire it; the DI-built engine always supplies it.
     */
    private val relationshipRepository: RelationshipWriteRepository? = null,
    private val editGate: EditGate = EditGate.AlwaysWritable,
    /** Writes shadow→real reconciliation links. Optional like [relationshipRepository]; DI supplies it. */
    private val reconciliationLinkRepository: ReconciliationLinkWriteRepository? = null,
    /**
     * Observations and the materialized rows built from them, with [personOverrideRepository] the overrides on top.
     * Optional like [relationshipRepository]: a batch with observation mutations fails without them. DI supplies both.
     */
    private val observationRepository: ObservationWriteRepository? = null,
    private val personOverrideRepository: PersonOverrideWriteRepository? = null,
    /**
     * Staged files' import records. Optional: without it a batch's [CsvImportMutation.RecordRows] are dropped, which
     * only costs a later re-import its fast path. DI supplies it.
     */
    private val importRecordRepository: ImportRecordWriteRepository? = null,
) : ImportEngine {
    private val writes = MutableStateFlow(0L)
    override val writeVersion: StateFlow<Long> = writes.asStateFlow()

    override suspend fun import(
        batch: ImportBatch,
        onProgress: (suspend (Progress) -> Unit)?,
        batchSize: Int,
    ): ImportResult =
        try {
            applyBatch(batch, onProgress, batchSize)
        } finally {
            writes.update { it + 1 }
        }

    private suspend fun applyBatch(
        batch: ImportBatch,
        onProgress: (suspend (Progress) -> Unit)?,
        batchSize: Int,
    ): ImportResult {
        editGate.ensureWritable()
        validate(batch)
        batch.shadowSource?.let { requireShadowOnlyReferences(batch, it) }

        // ----- Lookup-table resolution (first: ids feed attributes/relationships built by callers) -----
        val attributeTypeIds = batch.attributeTypeNames.associateWith { attributeTypeRepository.getOrCreate(it) }
        val relationshipTypeIds = batch.relationshipTypeNames.associateWith { relationshipTypeRepository.getOrCreate(it) }

        // ----- Config / staging / session / settings / device mutations -----
        // Read before a reset drops its file's observations: the transactions they shared with other records.
        val sharedByResets = transactionsObservedByResets(batch)
        val config = applyConfigMutations(batch)

        // ----- CREATE phase (first, so batch-local keys resolve for dependents) -----
        val createdCurrencyIds = mutableMapOf<LocalCurrencyKey, CurrencyId>()
        for (intent in batch.currencies.creates()) {
            createdCurrencyIds[intent.key] =
                currencyRepository.upsertCurrencyByCode(
                    requireNotNull(intent.code),
                    requireNotNull(intent.name),
                    intent.fractionDigits,
                    intent.source,
                )
        }
        val cryptoCreates = batch.cryptoAssets.creates()
        val createdCryptoIds: Map<LocalCryptoKey, CryptoId> =
            if (cryptoCreates.isEmpty()) {
                emptyMap()
            } else {
                cryptoCreates
                    .map { it.key }
                    .zip(
                        cryptoRepository.upsertCryptosByCode(
                            cryptoCreates.map { NewCryptoAsset(requireNotNull(it.code), it.name, it.source) },
                        ),
                    ).toMap()
            }
        // After the batch's currencies and crypto assets exist, as an alias names its asset by code.
        for (m in batch.assetAliasMutations) {
            when (m) {
                is AssetAliasMutation.Set -> cryptoRepository.setAssetAlias(m.alias, m.assetCode, m.publisher)
                is AssetAliasMutation.Remove -> cryptoRepository.removeAssetAlias(m.alias)
            }
        }
        val createdTradeIds = mutableMapOf<LocalTradeKey, TradeId>()
        val dedupedTradeKeys = mutableSetOf<LocalTradeKey>()

        // Multiset idempotency (see NewTrade.occurrence): N intents sharing the exact same
        // field tuple (e.g. an exchange order split into several byte-identical fills) must book as N
        // trades, not collapse to one. Count how many identical tuples this pass has already claimed and
        // pass that count as the occurrence index, so each one reuses a distinct existing row (re-import)
        // or inserts a distinct new one (first import). The key must group exactly the intents that
        // createTrades cannot tell apart, so the timestamp is the persisted millisecond value — an Instant
        // can carry sub-ms precision (e.g. Kraken's float epoch-seconds) that the trade row does not.
        // The description is deliberately absent: it plays no part in identifying a conversion (see
        // TradeSelect.sq's selectMatchingTradeId), so two rows describing one movement differently share
        // an occurrence counter and the second reuses the first's trade instead of double-booking.
        data class TradeTupleKey(
            val timestampMs: Long?,
            val fromAccountId: AccountId?,
            val fromAmount: Money?,
            val toAccountId: AccountId?,
            val toAmount: Money?,
        )
        val tradeOccurrences = mutableMapOf<TradeTupleKey, Int>()
        // Cross-source reconcile (opt-in): two sources describing one conversion rarely agree on the
        // exact tuple the writer matches on, so load the window they could disagree within and match in
        // memory. A hit suppresses the write and reports the existing trade, which is the only way a
        // trade can be linked to its duplicate (see TradeDedupePolicy).
        val tradeReconciler = buildTradeReconciler(batch)
        // The same movement again, but recorded by the other source as *transfers*: a conversion whose
        // credits it could not attribute to its debits. There is no trade id to report for such a
        // match, so the order links resolved below would have nothing to point at - hence trades an
        // order claims are held back from it. A conversion is never part of an order.
        val orderLinkedTradeKeys = batch.orders.flatMapTo(mutableSetOf()) { it.tradeKeys }
        val conversionReconciler = buildConversionTradeReconciler(batch, orderLinkedTradeKeys)
        val tradesToWrite = mutableListOf<Pair<ImportTradeIntent, NewTrade>>()
        for (intent in batch.trades.creates()) {
            // Either kind of cross-source match suppresses the write; they differ only in what can be
            // reported, so they are resolved together and the write is skipped once.
            val reconciledId = tradeReconciler?.match(intent)
            val reconciledConversionLeg =
                if (reconciledId == null) conversionReconciler?.match(intent) else null
            if (reconciledId != null || reconciledConversionLeg != null) {
                if (reconciledId != null) {
                    createdTradeIds[intent.key] = reconciledId
                    dedupedTradeKeys += intent.key
                }
                continue
            }
            val tupleKey =
                TradeTupleKey(
                    intent.timestamp?.toEpochMilliseconds(),
                    intent.fromAccountId,
                    intent.fromAmount,
                    intent.toAccountId,
                    intent.toAmount,
                )
            val occurrence = tradeOccurrences[tupleKey] ?: 0
            tradesToWrite +=
                intent to
                NewTrade(
                    timestamp = requireNotNull(intent.timestamp),
                    description = requireNotNull(intent.description),
                    fromAccountId = requireNotNull(intent.fromAccountId),
                    fromAmount = requireNotNull(intent.fromAmount),
                    toAccountId = requireNotNull(intent.toAccountId),
                    toAmount = requireNotNull(intent.toAmount),
                    source = intent.source,
                    occurrence = occurrence,
                    attributes = intent.attributes,
                )
            tradeOccurrences[tupleKey] = occurrence + 1
        }
        val tradeResults =
            if (tradesToWrite.isEmpty()) emptyList() else tradeRepository.createTrades(tradesToWrite.map { it.second })
        for ((intent, tradeResult) in tradesToWrite.map { it.first }.zip(tradeResults)) {
            // A re-imported trade loses the attributes its source owns but no longer reports (e.g. the
            // `excluded` of a trade the source has since un-deleted), like a transfer UPDATE.
            if (!tradeResult.created) {
                val reported = intent.attributes.mapTo(mutableSetOf()) { it.typeId }
                val stale = intent.ownedAttributes.filterKeys { it !in reported }
                if (stale.isNotEmpty()) {
                    tradeRepository
                        .getAttributes(tradeResult.id)
                        .first()
                        .filter { attr ->
                            stale.containsKey(attr.attributeType.id) &&
                                stale[attr.attributeType.id].let { it == null || attr.value in it }
                        }.forEach { tradeRepository.removeAttribute(tradeResult.id, it.attributeType.id) }
                }
            }
            createdTradeIds[intent.key] = tradeResult.id
            if (!tradeResult.created) dedupedTradeKeys += intent.key
        }
        val observer = sourceObservations()
        for ((intent, tradeResult) in tradesToWrite.map { it.first }.zip(tradeResults)) {
            if (tradeResult.created) {
                observer?.record(
                    intent.observationKey(),
                    intent.source,
                    tradeResult.id,
                ) { intent.observationContent() }
            }
        }
        for (intent in batch.orders) {
            val orderResult =
                exchangeOrderRepository.upsertOrder(
                    accountId = intent.accountId,
                    orderRef = intent.orderRef,
                    clientOid = intent.clientOid,
                    side = intent.side,
                    orderType = intent.orderType,
                    timeInForce = intent.timeInForce,
                    status = intent.status,
                    limitPrice = intent.limitPrice,
                    quantity = intent.quantity,
                    avgPrice = intent.avgPrice,
                    createdAt = intent.createdAt,
                    updatedAt = intent.updatedAt,
                    source = intent.source,
                )
            for (tradeKey in intent.tradeKeys) {
                // A dangling key means the producer's trade/order keys diverged — losing the link
                // silently would be far worse than failing the import.
                val tradeId =
                    requireNotNull(createdTradeIds[tradeKey]) {
                        "Order ${intent.orderRef} references trade key '${tradeKey.value}' absent from this batch"
                    }
                exchangeOrderRepository.linkTrade(orderResult.id, tradeId)
            }
        }
        val createdCategoryIds = mutableMapOf<LocalCategoryKey, Long>()
        for (intent in batch.categories.creates()) {
            createdCategoryIds[intent.key] =
                categoryRepository.createCategory(
                    Category(name = requireNotNull(intent.name), parentId = intent.parentId),
                    intent.source,
                )
        }

        onProgress?.invoke(Progress("Resolving accounts"))
        val accountResolution = resolveAccounts(batch.copy(accountsToCreate = batch.accountsToCreate.creates()))
        batch.shadowSource?.let { source ->
            val shadowIds = shadowAccountIds(source)
            val strays = accountResolution.keyToId.values.filterNot { it in shadowIds }
            require(strays.isEmpty()) { "Reconciliation import for '$source' resolved onto non-shadow account(s) $strays" }
        }

        onProgress?.invoke(Progress("Resolving people"))
        val personResolution = resolvePeople(batch.copy(peopleToCreate = batch.peopleToCreate.creates()))

        onProgress?.invoke(Progress("Linking ownerships"))
        createOwnerships(
            batch.copy(ownerships = batch.ownerships.creates()),
            personResolution,
            accountResolution.keyToId,
        )

        val transfers = importTransferCreates(batch.transfers.creates(), batch, accountResolution.keyToId, batchSize, onProgress)

        // ----- UPDATE phase -----
        // A person's edits become their overrides as well, so a rebuild from the observations keeps them.
        val handEdits = handEditSnapshots(batch.transfers.updates())
        transactionRepository.updateTransfers(
            batch.transfers.updates().map { intent ->
                TransferEdit(
                    transfer = intent.toUpdatedTransfer(),
                    transactionId = requireNotNull(intent.existingId),
                    source = intent.source,
                    deletedAttributeIds = intent.deletedAttributeIds,
                    updatedAttributes = intent.updatedAttributes,
                    newAttributes = intent.attributes,
                )
            },
        )
        recordHandEdits(handEdits)
        tradeRepository.updateTrades(
            batch.trades.updates().map { intent ->
                TradeEdit(
                    trade =
                        Trade(
                            id = requireNotNull(intent.existingId),
                            timestamp = requireNotNull(intent.timestamp),
                            description = requireNotNull(intent.description),
                            fromAccountId = requireNotNull(intent.fromAccountId),
                            from = requireNotNull(intent.fromAmount),
                            toAccountId = requireNotNull(intent.toAccountId),
                            to = requireNotNull(intent.toAmount),
                        ),
                    source = intent.source,
                )
            },
        )
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

        // ----- RELATIONSHIPS between existing transactions (after the writes, so a batch can relate what it made) -----
        if (batch.relationshipMutations.isNotEmpty()) {
            val relationships = checkNotNull(relationshipRepository) { "This engine has no relationship repository" }
            relationships.apply(
                batch.relationshipMutations.map { m ->
                    when (m) {
                        is RelationshipMutation.Create -> RelationshipWrite.Create(m.typeId, m.members, m.source)
                        is RelationshipMutation.Delete -> RelationshipWrite.Delete(m.id)
                    }
                },
            )
        }

        // ----- MERGE / UNMERGE (before deletes: a merge already removes the absorbed account) -----
        batch.accountMerges.forEach { accountRepository.mergeAccounts(it.deletedId, it.survivingId, it.mapDeletedName) }
        batch.accountUnmerges.forEach { accountRepository.unmergeAccount(it) }

        // ----- DELETE phase (dependents first) -----
        batch.ownerships.deletes().forEach { ownershipRepository.deleteOwnership(requireNotNull(it.existingId)) }
        // Transfers and trades are the only deletes a batch holds in bulk (a re-import removes every row
        // its session created before re-running it), so they report progress like the write phase does —
        // otherwise a caller's bar holds still through the slower half of a re-import.
        val transferDeletes = batch.transfers.deletes()
        val tradeDeletes = batch.trades.deletes()
        val deleteTotal = transferDeletes.size + tradeDeletes.size
        var deleted = 0

        suspend fun reportDeleted(count: Int) {
            deleted += count
            onProgress?.invoke(
                Progress(
                    detail = "Deleting transactions",
                    fraction = deleted.toFloat() / deleteTotal,
                    processed = deleted,
                    total = deleteTotal,
                ),
            )
        }

        // A transaction another record still observes (a joined twin) outlives its creator: it is rebuilt from what is
        // left, as is one that only lost a reset file's observations.
        val transferDeleteIds = transferDeletes.map { requireNotNull(it.existingId) }
        val tradeDeleteIds = tradeDeletes.map { requireNotNull(it.existingId) }
        val stillObserved =
            stillObserved(
                // A person deleting a transaction deletes it; only a source withdrawing its records leaves the others standing.
                transferDeletes.filter { it.source.isImport() }.map { requireNotNull(it.existingId) } +
                    tradeDeletes.filter { it.source.isImport() }.map { requireNotNull(it.existingId) },
                (transferDeletes.map { it.source } + tradeDeletes.map { it.source }).toSet(),
            )
        transferDeleteIds.filterNot { it in stillObserved }.chunked(DELETE_CHUNK_ROWS).forEach { chunk ->
            observationRepository?.deleteObservationsOfTransactions(chunk)
            transactionRepository.deleteTransactions(chunk)
            reportDeleted(chunk.size)
        }
        tradeDeleteIds.filterNot { it in stillObserved }.chunked(DELETE_CHUNK_ROWS).forEach { chunk ->
            observationRepository?.deleteObservationsOfTransactions(chunk)
            tradeRepository.deleteTrades(chunk)
            reportDeleted(chunk.size)
        }
        // A transaction the reset left with no observation at all stays as its import booked it until the file's re-import:
        // rebuilding it from nothing would delete what the reset file's other rows still report.
        val rebuilt =
            (sharedByResets - (transferDeleteIds + tradeDeleteIds).toSet()).filterTo(mutableSetOf()) {
                observationRepository?.getObservationsOfTransaction(it)?.isNotEmpty() == true
            } + stillObserved
        if (rebuilt.isNotEmpty()) materialize(emptyList(), MaterializationRequest(Source.Reconciler, rebuilt))
        // An account an observation still names isn't empty, even when no transaction shows it: a joined twin's
        // other record keeps its own account (a placeholder) while the merge books the real one.
        val accountDeletes = batch.accountsToCreate.deletes().map { requireNotNull(it.existingId) }
        val observed = observationRepository?.getAccountsWithObservations(accountDeletes).orEmpty()
        accountRepository.deleteAccounts(accountDeletes.filter { it !in observed })
        batch.categories.deletes().forEach { categoryRepository.deleteCategory(requireNotNull(it.existingId)) }
        batch.currencies.deletes().forEach { currencyRepository.deleteCurrency(requireNotNull(it.existingId)) }
        batch.cryptoAssets.deletes().forEach { cryptoRepository.deleteCryptoAsset(requireNotNull(it.existingId)) }
        batch.peopleToCreate.deletes().forEach { personRepository.deletePerson(requireNotNull(it.existingId)) }

        // ----- OBSERVATIONS (last: they name the accounts and assets the batch created) -----
        val materialization = batch.materialization?.let { materialize(batch.observationMutations, it) } ?: MaterializationReport()
        // A duplicate row joins its observation to a transaction another source materialized: bring that transaction to
        // the merge of both, so it says what both records say.
        if (transfers.observedDuplicates.isNotEmpty()) {
            materialize(emptyList(), MaterializationRequest(Source.Reconciler, transfers.observedDuplicates))
        }
        val matchSync = batch.takeIf { it.syncMatches }?.let { matchSync()?.run() } ?: MaterializationReport()

        return ImportResult(
            accountsCreated = accountResolution.created,
            peopleCreated = personResolution.createdCount,
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
            dedupedTradeKeys = dedupedTradeKeys,
            attributeTypeIds = attributeTypeIds,
            relationshipTypeIds = relationshipTypeIds,
            createdCsvStrategyIds = config.csvStrategyIds,
            createdApiStrategyIds = config.apiStrategyIds,
            createdAccountMappingIds = config.accountMappingIds,
            createdCsvImportIds = config.csvImportIds,
            createdImportDirectoryIds = config.importDirectoryIds,
            apiCredentialIds = config.apiCredentialIds,
            apiSessionIds = config.apiSessionIds,
            apiRequestIds = config.apiRequestIds,
            apiResponseIds = config.apiResponseIds,
            materialization = materialization,
            matchSync = matchSync,
        )
    }

    private suspend fun materialize(
        mutations: List<ObservationMutation>,
        request: MaterializationRequest,
    ): MaterializationReport =
        ObservationMaterialization(
            observations = checkNotNull(observationRepository) { "This engine has no observation repository" },
            overrides = checkNotNull(personOverrideRepository) { "This engine has no person override repository" },
            accounts = accountRepository,
            trades = tradeRepository,
            relationships = relationshipRepository,
            placeholderTypeId = attributeTypeRepository.getByName(WellKnownIds.UNIDENTIFIED_COUNTERPARTY_ATTR_TYPE_NAME).first()?.id,
        ).run(mutations, request)

    /** The transactions the records of the files and API sessions [batch] resets observe, read before the reset drops them. */
    private suspend fun transactionsObservedByResets(batch: ImportBatch): Set<TransactionId> {
        val observations = observationRepository ?: return emptySet()
        val records = importRecordRepository ?: return emptySet()
        val resetIds =
            batch.csvImportMutations.mapNotNullTo(mutableSetOf()) {
                when (it) {
                    is CsvImportMutation.ResetRowStatuses -> it.id
                    is CsvImportMutation.ResetToUnimported -> it.id
                    else -> null
                }
            }
        val resetSessions =
            batch.apiSessionMutations.mapNotNullTo(mutableSetOf()) { (it as? ApiSessionMutation.ResetToUnimported)?.sessionId }
        return (resetIds.flatMap { records.getCsvImportRecords(it) } + resetSessions.flatMap { records.getApiSessionRecords(it) })
            .flatMap { observations.getObservationsOf(ObservationOwner.Record(it.id)) }
            .mapTo(mutableSetOf()) { it.transactionId }
    }

    /**
     * Which of [ids] another source's record still observes, once the batch's resets dropped its own sources'. The
     * deleting sources' own records don't count: a file being unimported still has its records until a later reset.
     */
    private suspend fun stillObserved(
        ids: List<TransactionId>,
        deletingSources: Set<Source>,
    ): Set<TransactionId> {
        val observations = observationRepository ?: return emptySet()
        val records = importRecordRepository ?: return emptySet()
        val own =
            (
                deletingSources
                    .filterIsInstance<Source.Csv>()
                    .map { it.importId }
                    .distinct()
                    .flatMap { records.getCsvImportRecords(it) } +
                    deletingSources.filterIsInstance<Source.Api>().map { it.sessionId }.distinct().flatMap {
                        records.getApiSessionRecords(
                            it,
                        )
                    }
            ).mapTo(mutableSetOf()) { it.id }
        return ids.filterTo(mutableSetOf()) { id ->
            observations.getObservationsOfTransaction(id).any {
                val owner = it.owner
                owner is ObservationOwner.Record &&
                    owner.recordId !in own
            }
        }
    }

    /** The match sync, or null on an engine without observations (the hand-built ones tests use). */
    private suspend fun matchSync(): MatchSync? {
        val observations = observationRepository ?: return null
        val importRecords = importRecordRepository ?: return null
        val calendars =
            holidayCalendarRepository?.let { repository ->
                val locations =
                    accountAttributeRepository
                        .getByType(AttributeTypeId(WellKnownIds.ACCOUNT_LOCATION_ATTR_TYPE_ID))
                        .first()
                        .associate { it.accountId to it.value }
                SettlementCalendars(locations, settingsRepository.getDefaultLocation().first(), repository.getAll().first())
            } ?: SettlementCalendars.WEEKENDS_ONLY
        return MatchSync(
            loader =
                MatchScopeLoader(
                    observations,
                    importRecords,
                    csvImportRepository,
                    apiSessionRepository,
                    accountAttributeRepository,
                    attributeTypeRepository,
                ),
            strategies = csvImportStrategyRepository,
            apiStrategies = apiImportStrategyRepository,
            attributeTypes = attributeTypeRepository,
            accounts = accountRepository,
            records = importRecords,
            csvImports = csvImportRepository,
            relationships = relationshipRepository,
            calendarFor = calendars::forAccounts,
            materialize = ::materialize,
        )
    }

    /**
     * Records what API items and hand entries say about the transactions this batch books, beside the rows themselves
     * (#1429, #1430): the observations staged files will be matched against once they sync (#1428). Null without the
     * repositories it writes to.
     */
    private suspend fun sourceObservations(): SourceObservations? {
        val observations = observationRepository ?: return null
        val unidentified =
            attributeTypeRepository.getByName(WellKnownIds.UNIDENTIFIED_COUNTERPARTY_ATTR_TYPE_NAME).first()?.id
        return SourceObservations(observations, importRecordRepository, unidentified)
    }

    /** Deletes [sessionId]'s import records, which restrict the session's deletion, and their observations first. */
    private suspend fun deleteSessionRecords(sessionId: ApiSessionId) {
        val records = importRecordRepository ?: return
        val observations = observationRepository
        for (record in records.getApiSessionRecords(sessionId)) {
            observations?.getObservationsOf(ObservationOwner.Record(record.id))?.forEach { observations.deleteObservation(it.id) }
            records.deleteRecord(record.id)
        }
    }

    /**
     * Writes one observation per transaction an API item or a person booked. An API item owns its observations through
     * its import record (created on first sight, its content not hashed until the API sync, #1429, hashes it); a hand
     * entry through the provenance row that created the transaction. Other sources' bookings are left to their sync.
     */
    private class SourceObservations(
        private val observations: ObservationWriteRepository,
        private val records: ImportRecordWriteRepository?,
        private val unidentifiedCounterpartyTypeId: AttributeTypeId?,
    ) {
        // Each staged file's or API session's records, read once per batch: the container they belong to, then their keys.
        private val containerRecords = mutableMapOf<Any, MutableMap<ImportRecordKey, ImportRecordId>>()
        private val groups = mutableMapOf<ImportGroupKey, ImportGroupId>()

        fun transferContent(
            leg: ExpandedTransfer,
            approximateTime: Boolean,
        ): ObservationContent = leg.observationContent(approximateTime, unidentifiedCounterpartyTypeId)

        /** Records [content] for [key] (null: a trade no record backs) on [transactionId], when [provenance] says whose. */
        suspend fun record(
            key: ObservationKey?,
            provenance: Source,
            transactionId: TransactionId,
            content: () -> ObservationContent,
        ) {
            val slot = key?.slot ?: ObservationSlot.TRADE
            val (owner, fresh) = owner(key?.owner, provenance, transactionId) ?: return
            // A record seen before may already say this slot (a trade its re-import found again): it keeps what it said.
            if (!fresh && observations.getObservationsOf(owner).any { it.slot == slot }) return
            observations.insertObservation(owner, slot, transactionId, content())
        }

        /** The owner, and whether it is new (so says nothing yet); null for a booking no API item or person made. */
        private suspend fun owner(
            key: ObservationOwnerKey?,
            provenance: Source,
            transactionId: TransactionId,
        ): Pair<ObservationOwner, Boolean>? {
            val recordKey = (key as? ObservationOwnerKey.Record)?.key
            return when {
                recordKey != null -> {
                    record(recordKey)
                }

                key is ObservationOwnerKey.Group -> {
                    group(key)
                }

                provenance == Source.Manual && (key == null || key is ObservationOwnerKey.Unrecorded) -> {
                    observations.getCreationSourceId(transactionId)?.let { ObservationOwner.Manual(it) to true }
                }

                else -> {
                    null
                }
            }
        }

        // A staged row's record is normally written once its run completes (with its content hash), so one met first here gets
        // a hash that matches nothing, which that write then replaces: until it does, the file never looks unchanged.
        private suspend fun record(key: ImportRecordKey): Pair<ObservationOwner, Boolean>? {
            val records = records ?: return null
            val known =
                when (key) {
                    is ImportRecordKey.ApiItem -> {
                        containerRecords.getOrPut(key.sessionId) {
                            records.getApiSessionRecords(key.sessionId).associateTo(mutableMapOf()) { it.key to it.id }
                        }
                    }

                    is ImportRecordKey.CsvRow -> {
                        containerRecords.getOrPut(key.csvImportId) {
                            records.getCsvImportRecords(key.csvImportId).associateTo(mutableMapOf()) { it.key to it.id }
                        }
                    }
                }
            known[key]?.let { return ObservationOwner.Record(it) to false }
            val id = records.insertRecord(key, RECORD_UNHASHED)
            known[key] = id
            return ObservationOwner.Record(id) to true
        }

        // A group outlives each of its runs (its legs may come from several files), so it may already say a slot. Each run
        // names every leg it joined, so its members are those of the latest run.
        private suspend fun group(key: ObservationOwnerKey.Group): Pair<ObservationOwner, Boolean>? {
            val records = records ?: return null
            val id = groups.getOrPut(key.key) { records.getOrCreateGroup(key.key) }
            if (key.members.isNotEmpty()) {
                val members = key.members.mapNotNullTo(mutableSetOf()) { (record(it)?.first as? ObservationOwner.Record)?.recordId }
                records.setGroupMembers(id, members)
            }
            return ObservationOwner.Group(id) to false
        }
    }

    // region Operation dispatch

    private fun <T : WriteIntent> List<T>.creates() = filter { it.operation == ImportOperation.CREATE }

    private fun <T : WriteIntent> List<T>.updates() = filter { it.operation == ImportOperation.UPDATE }

    private fun <T : WriteIntent> List<T>.deletes() = filter { it.operation == ImportOperation.DELETE }

    /** Fails loudly when an intent omits the fields its [ImportOperation] requires (nullable-by-design). */
    private fun validate(batch: ImportBatch) {
        require(batch.observationMutations.isEmpty() || batch.materialization != null) {
            "observation mutations need a materialization request"
        }
        batch.transfers.forEach {
            when (it.operation) {
                ImportOperation.CREATE -> {
                    require(it.fromAccount != null && it.toAccount != null && it.timestamp != null && it.amount != null) {
                        "CREATE transfer requires fromAccount/toAccount/timestamp/amount"
                    }
                }

                ImportOperation.UPDATE, ImportOperation.DELETE -> {
                    require(it.existingId != null) { "${it.operation} transfer requires existingId" }
                }
            }
        }
        batch.trades.forEach {
            when (it.operation) {
                ImportOperation.CREATE, ImportOperation.UPDATE -> {
                    require(
                        it.timestamp != null && it.description != null &&
                            it.fromAccountId != null && it.fromAmount != null &&
                            it.toAccountId != null && it.toAmount != null,
                    ) {
                        "${it.operation} trade requires timestamp/description/fromAccountId/" +
                            "fromAmount/toAccountId/toAmount"
                    }
                    if (it.operation == ImportOperation.UPDATE) {
                        require(it.existingId != null) { "UPDATE trade requires existingId" }
                    }
                }

                ImportOperation.DELETE -> {
                    require(it.existingId != null) { "DELETE trade requires existingId" }
                }
            }
        }
        batch.accountsToCreate.forEach {
            when (it.operation) {
                ImportOperation.CREATE -> {
                    require((it.name != null || it.match is AccountMatchKey.FeeAccountOf) && it.openingDate != null) {
                        "CREATE account requires name/openingDate"
                    }
                }

                ImportOperation.UPDATE, ImportOperation.DELETE -> {
                    require(it.existingId != null) { "${it.operation} account requires existingId" }
                }
            }
        }
        batch.categories.forEach {
            when (it.operation) {
                ImportOperation.CREATE -> {
                    require(it.name != null) { "CREATE category requires name" }
                }

                ImportOperation.UPDATE -> {
                    require(it.existingId != null && it.category != null) { "UPDATE category requires existingId and category" }
                }

                ImportOperation.DELETE -> {
                    require(it.existingId != null) { "DELETE category requires existingId" }
                }
            }
        }
        batch.currencies.forEach {
            when (it.operation) {
                ImportOperation.CREATE -> {
                    require(it.code != null && it.name != null) { "CREATE currency requires code/name" }
                }

                ImportOperation.UPDATE -> {
                    require(it.currency != null) { "UPDATE currency requires currency" }
                }

                ImportOperation.DELETE -> {
                    require(it.existingId != null) { "DELETE currency requires existingId" }
                }
            }
        }
        batch.peopleToCreate.forEach {
            when (it.operation) {
                ImportOperation.CREATE -> {
                    require(it.firstName != null) { "CREATE person requires firstName" }
                }

                ImportOperation.UPDATE, ImportOperation.DELETE -> {
                    require(it.existingId != null) { "${it.operation} person requires existingId" }
                }
            }
        }
        batch.ownerships.forEach {
            when (it.operation) {
                ImportOperation.CREATE -> {
                    require((it.personKey != null || it.existingPersonId != null) && it.account != null) {
                        "CREATE ownership requires a person and an account"
                    }
                }

                ImportOperation.DELETE -> {
                    require(it.existingId != null) { "DELETE ownership requires existingId" }
                }

                ImportOperation.UPDATE -> {
                    error("Ownerships have no UPDATE operation")
                }
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
        /** The existing transactions this batch's duplicate rows now also observe, which their merge must take in. */
        val observedDuplicates: Set<TransactionId> = emptySet(),
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
        onProgress: (suspend (Progress) -> Unit)?,
    ): TransferOutcome {
        if (creates.isEmpty()) return TransferOutcome(0, 0, 0, 0, emptyMap(), emptyMap(), emptyList())

        var manualRowIndex = 0L
        val resolvedTransfers =
            creates.map { transfer ->
                val fee = transfer.fee
                val passThrough = transfer.passThrough
                transfer.copy(
                    rowKey = transfer.rowKey ?: ImportRowKey.Manual(manualRowIndex++),
                    source =
                        transfer.statementAccount
                            ?.let { transfer.source.withStatementAccount(requireId(resolveRef(it, accountKeyToId))) }
                            ?: transfer.source,
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

        onProgress?.invoke(Progress("Detecting duplicates"))
        val calendars = settlementCalendars(batch.dedupePolicy)
        val existing = loadExisting(resolvedTransfers, batch, calendars)
        val existingRelationships = existingRelationships(existing)
        val otherSideLegs = otherSideLegs(resolvedTransfers, batch, calendars)
        val classified =
            ImportDeduper(
                batch.dedupePolicy,
                existing,
                // The other-side candidates lie on any accounts, so their claims are read too: a record another source
                // already reconciled against has both its ends named and stands in for no one.
                (existingRelationships + otherSideRelationships(otherSideLegs, existing))
                    .filter { it.relationshipType.id.id == WellKnownIds.RECONCILED_RELATIONSHIP_TYPE_ID }
                    .mapNotNullTo(mutableSetOf()) { it.transferPair?.second },
                reconciledInto =
                    existingRelationships
                        .filter { it.relationshipType.id.id == WellKnownIds.RECONCILED_RELATIONSHIP_TYPE_ID }
                        .mapNotNull { it.transferPair }
                        .toMap(),
                ownBatchAccounts = resolvedTransfers.flatMapTo(mutableSetOf()) { listOf(it.fromAccount, it.toAccount).map(::requireId) },
                passThroughOnward = passThroughOnward(existing, existingRelationships),
                ownSourceLegs = ownSourceLegs(resolvedTransfers, existing),
                calendars = calendars,
                otherSideLegs = otherSideLegs,
                otherSideStatementAccounts = csvImportRepository.getStatementAccounts(otherSideLegs.map { it.id }),
            ).classify(resolvedTransfers)

        val toImport = resolveReversalLinks(classified.filter { it.status == ImportStatus.IMPORTED })
        val toUpdate = classified.filter { it.status == ImportStatus.UPDATED } + internalReconcileExclusions(classified)
        val duplicates = classified.count { it.status == ImportStatus.DUPLICATE }
        val excluded = resolvedTransfers.count { it.excludedFromBalances }

        onProgress?.invoke(Progress("Importing transactions", fraction = 0f, processed = 0, total = toImport.size))
        // The engine's own cross-source reconcile marker is never "owned" by a source: a row that
        // re-imports must not un-exclude a transfer the engine reconciled away.
        val engineOwnedTypeId =
            when (val policy = batch.dedupePolicy) {
                is DedupePolicy.FuzzyAllFields -> policy.reconciledExclusionAttributeTypeId
                is DedupePolicy.UniqueIdentifier -> policy.reconciledExclusionAttributeTypeId
                is DedupePolicy.ApiMultiKey -> policy.reconciledExclusionAttributeTypeId
                else -> null
            }
        val createdLegs = mutableListOf<Pair<ExpandedTransfer, TransferId>>()
        val createdIds = writeTransfers(toImport, toUpdate, batchSize, onProgress, engineOwnedTypeId, createdLegs)
        val observer = sourceObservations()
        if (observer != null) {
            val approximate =
                toImport.mapNotNullTo(
                    mutableSetOf(),
                ) { c -> c.transfer.takeIf { it.approximateUntil != null }?.observationKey() }
            val rewritten = toImport.mapNotNull { c -> c.observed?.let { c.transfer.observationKey() to it } }.toMap()
            for ((leg, id) in createdLegs) {
                val said = rewritten[leg.key]?.let { row -> leg.asSaidBy(row) } ?: leg
                observer.record(leg.key, leg.provenance, id) { observer.transferContent(said, leg.key in approximate) }
            }
        }
        writeConversionTrades(toImport, observer)

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
        val observedDuplicates =
            if (observer != null) observeDuplicates(classified, orderedRowOutcomes, existingRelationships, observer) else emptySet()

        return TransferOutcome(
            imported = toImport.size,
            duplicates = duplicates,
            updated = toUpdate.size,
            excluded = excluded,
            createdTransferIds = createdTransferIds,
            rowOutcomes = rowOutcomes,
            orderedRowOutcomes = orderedRowOutcomes,
            observedDuplicates = observedDuplicates,
        )
    }

    /**
     * A row that duplicates a transfer already booked (or one this batch books) says that transfer too: its main leg,
     * and its fee leg when the transfer has a fee, observe it beside what booked it, so twins are one transaction with
     * several observations. A row seen before (a re-import of its file) already says its slots and keeps them.
     */
    private suspend fun observeDuplicates(
        classified: List<Classified>,
        outcomes: List<RowOutcome>,
        existingRelationships: List<Relationship>,
        observer: SourceObservations,
    ): Set<TransactionId> {
        val duplicates =
            classified.zip(outcomes).mapNotNull { (c, outcome) ->
                outcome.transferId?.takeIf { c.status == ImportStatus.DUPLICATE }?.let { c to it }
            }
        if (duplicates.isEmpty()) return emptySet()
        val observed = mutableSetOf<TransactionId>()
        val feeLegs =
            existingRelationships
                .mapNotNull { relationship ->
                    relationship.transferPair?.let { (primary, related) -> (primary to relationship.relationshipType.id) to related }
                }.toMap()
        val rows = ObservationExpander.expandTransfers(duplicates.map { (c, _) -> ExpansionRow(c.transfer) }, emptyMap())
        for ((duplicate, row) in duplicates.zip(rows)) {
            val (c, id) = duplicate
            val approximate = c.transfer.approximateUntil != null
            observer.record(row.main.key, row.main.provenance, id) { observer.transferContent(row.main, approximate) }
            observed += id
            val feeLeg = row.legs.firstOrNull { it.key.slot == ObservationSlot.FEE }
            val feeId = c.transfer.fee?.let { feeLegs[id to it.relationshipTypeId] }
            if (feeLeg != null && feeId != null) {
                observer.record(feeLeg.key, feeLeg.provenance, feeId) { observer.transferContent(feeLeg, approximate) }
                observed += feeId
            }
        }
        return observed
    }

    /**
     * Books each created transfer's [conversion][ImportTransfer.conversion] as a trade on its own account: settled → foreign for
     * a payment, foreign → settled for a refund. Only created transfers get one (a DUPLICATE's trade already
     * exists), and a trade is excluded with its transfer, whether the source excluded the row or a
     * reconcile did — otherwise the conversion of money that was never spent would still move balances.
     * Identical conversions within the batch get distinct occurrences, as the trade writer requires.
     */
    private suspend fun writeConversionTrades(
        toImport: List<Classified>,
        observer: SourceObservations?,
    ) {
        val occurrences = mutableMapOf<List<Any>, Int>()
        val expanded = toImport.mapNotNull { ObservationExpander.expandConversion(it.transfer) }
        val trades =
            expanded.map { trade ->
                // Keyed on the persisted millisecond, as createTrades matches on it (see the leg-group trade loop).
                val key = listOf(trade.timestamp.toEpochMilliseconds(), trade.accountId, trade.fromAmount, trade.toAmount)
                val occurrence = occurrences[key] ?: 0
                occurrences[key] = occurrence + 1
                NewTrade(
                    timestamp = trade.timestamp,
                    description = trade.description,
                    fromAccountId = trade.accountId,
                    fromAmount = trade.fromAmount,
                    toAccountId = trade.accountId,
                    toAmount = trade.toAmount,
                    source = trade.provenance,
                    occurrence = occurrence,
                    attributes = trade.attributes,
                )
            }
        if (trades.isEmpty()) return
        val results = tradeRepository.createTrades(trades)
        if (observer == null) return
        for ((trade, result) in expanded.zip(results)) {
            if (result.created) observer.record(trade.key, trade.provenance, result.id) { trade.observationContent() }
        }
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
        val existingCandidates = loadReversalCandidates(toImport, reversalTypeId)
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
                    existingCandidates[ReversalCandidateKey(spendTarget, spendSource, amount)]
                        ?.firstOrNull { it.timestamp <= timestamp && it.id !in claimedExisting }

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

                        else -> {
                            null
                        }
                    }
                if (link != null) links[legIndex] = link
                batchSpendLegs += BatchSpendLeg(index, legIndex, spendSource, spendTarget, amount, timestamp)
            }
            if (links.isEmpty()) classified else classified.copy(reversalLinks = links)
        }
    }

    /**
     * Loads the existing-DB reversal candidates for every pass-through spend leg of [toImport] in one
     * query, grouped by directed (source, target, amount), newest first within each group. Every spend
     * leg has at least one conduit endpoint, so bounding the query by the batch's conduit accounts (and
     * the legs' amounts and max timestamp) covers all per-leg lookups; the currency check the old
     * per-leg query did via asset_id happens here through the [Money] group key. The DB is not written
     * while legs are resolved, so this snapshot is equivalent to querying per leg.
     */
    private suspend fun loadReversalCandidates(
        toImport: List<Classified>,
        reversalTypeId: RelationshipTypeId,
    ): Map<ReversalCandidateKey, List<Transfer>> {
        val conduitAccounts = mutableSetOf<AccountId>()
        val amounts = mutableSetOf<Money>()
        var maxTimestamp: Instant? = null
        for (classified in toImport) {
            val passThrough = classified.transfer.passThrough ?: continue
            passThrough.conduits.forEach { conduitAccounts += it.requireExistingId() }
            amounts += passThrough.amount
            val timestamp = requireNotNull(classified.transfer.timestamp)
            if (maxTimestamp == null || timestamp > maxTimestamp) maxTimestamp = timestamp
        }
        if (conduitAccounts.isEmpty() || maxTimestamp == null) return emptyMap()
        return transactionRepository
            .getUnreversedTransfersTouchingAccounts(conduitAccounts, amounts, maxTimestamp, reversalTypeId)
            .groupBy { ReversalCandidateKey(it.sourceAccountId, it.targetAccountId, it.amount) }
    }

    /** Directed reversal-candidate lookup key: candidates running source → target of exactly amount. */
    private data class ReversalCandidateKey(
        val source: AccountId,
        val target: AccountId,
        val amount: Money,
    )

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

        // A shadow-only batch must never match (and so reuse) a real account by name: only the source's
        // own shadow accounts are candidates, and a name clash with a real account creates a suffixed one.
        val shadowScope = batch.shadowSource?.let { shadowAccountIds(it) }
        val byName =
            accountRepository
                .getAllAccounts()
                .first()
                .filter { shadowScope == null || it.id in shadowScope }
                .associate { it.name to it.id }
                .toMutableMap()
        val existingAccountAttrIndex = buildExistingAccountAttrIndex(batch.accountsToCreate)
        val byAttr = existingAccountAttrIndex.byAttr
        val claimedAccountIds = existingAccountAttrIndex.claimedAccountIds
        val byPersonalKey = buildExistingPersonalKeyIndex(batch.accountsToCreate)
        // Names of accounts created earlier in THIS batch, so a later same-named intent with no other
        // way to match reuses that account instead of forking a duplicate. Kept separate from [byName]
        // (which also holds pre-existing accounts) because the name fallback must not reach across to a
        // pre-existing account that merely shares a name — see [matchAccount].
        val batchCreatedByName = mutableMapOf<String, AccountId>()

        val keyToId = mutableMapOf<LocalAccountKey, AccountId>()
        var created = 0
        val shadowTag =
            batch.shadowSource?.let { NewAttribute(AttributeTypeId(WellKnownIds.ACCOUNT_RECONCILIATION_SOURCE_ATTR_TYPE_ID), it) }
        // A fee account names the account it belongs to, which may be another intent of this batch: resolve it last.
        val (feeIntents, otherIntents) = batch.accountsToCreate.partition { it.match is AccountMatchKey.FeeAccountOf }
        for (rawIntent in otherIntents) {
            val intent =
                if (shadowTag == null || rawIntent.attributes.any { it.typeId == shadowTag.typeId }) {
                    rawIntent
                } else {
                    rawIntent.copy(attributes = rawIntent.attributes + shadowTag)
                }
            val match = matchAccount(intent, byName, byAttr, byPersonalKey, batchCreatedByName, claimedAccountIds)
            if (match != null) {
                // A bank-identity (adopted) match re-points the existing account onto this intent only for
                // own/source accounts; counterparties merge in silently keeping the existing account as-is.
                if (match.adopted && intent.adoptOnBankMatch) {
                    adoptAccount(intent, match.id, byName, byAttr)
                    // Now carries this intent's identity attributes: a later same-batch intent that would
                    // otherwise adopt it by the "unclaimed name" fallback must see it as claimed.
                    claimedAccountIds += match.id
                }
                keyToId[intent.key] = match.id
                continue
            }
            val requestedName = requireNotNull(intent.name)
            val resolvedName = uniqueName(requestedName, intent, byName)
            val newId = createAccount(intent, resolvedName)
            created++
            keyToId[intent.key] = newId
            indexNewAccount(intent, newId, resolvedName, byName, byAttr, byPersonalKey)
            // Keyed by the REQUESTED name (matchAccount's lookup key), not resolvedName: a later
            // same-batch intent asking for the same name (e.g. a second external id for the same
            // merchant) must find this account even when resolvedName was suffixed to dodge a
            // pre-existing account's UNIQUE collision.
            batchCreatedByName.getOrPut(requestedName) { newId }
        }
        if (feeIntents.isNotEmpty()) {
            val nameById = byName.entries.associate { (name, id) -> id to name }.toMutableMap()
            for (rawIntent in feeIntents) {
                val intent = if (shadowTag == null) rawIntent else rawIntent.copy(attributes = rawIntent.attributes + shadowTag)
                val resolved = resolveFeeAccount(intent, keyToId, byName, byAttr, nameById)
                if (resolved.created) created++
                keyToId[intent.key] = resolved.id
            }
        }
        return AccountResolution(keyToId, created)
    }

    private class ResolvedFeeAccount(
        val id: AccountId,
        val created: Boolean,
    )

    /**
     * The fee account of an [AccountMatchKey.FeeAccountOf] intent's account: the one tagged as its fee account, else a new one
     * named "<account> Fees". Only the tag finds it: an untagged account of that name is not a fee account, and the new one
     * takes a suffixed name beside it. A shadow account's new fee account is a shadow account of the same source. [nameById]
     * holds every account the batch can see, those it created included.
     */
    private suspend fun resolveFeeAccount(
        intent: ImportAccountIntent,
        keyToId: Map<LocalAccountKey, AccountId>,
        byName: MutableMap<String, AccountId>,
        byAttr: MutableMap<Pair<AttributeTypeId, String>, AccountId>,
        nameById: MutableMap<AccountId, String>,
    ): ResolvedFeeAccount {
        val owner =
            when (val ref = (intent.match as AccountMatchKey.FeeAccountOf).account) {
                is AccountRef.Existing -> ref.id
                is AccountRef.Local -> requireNotNull(keyToId[ref.key]) { "Fee account of an unresolved account ${ref.key}" }
            }
        val tag = NewAttribute(FEE_ACCOUNT_FOR_TYPE, owner.id.toString())
        byAttr[tag.typeId to tag.value]?.let { return ResolvedFeeAccount(it, created = false) }
        val desired = "${requireNotNull(nameById[owner]) { "Fee account of unknown account $owner" }} Fees"
        // A shadow account's fee account belongs to the same reconciliation source, whichever batch creates it.
        val shadowType = AttributeTypeId(WellKnownIds.ACCOUNT_RECONCILIATION_SOURCE_ATTR_TYPE_ID)
        val shadowTag =
            if (intent.attributes.any { it.typeId == shadowType }) {
                null
            } else {
                accountAttributeRepository.getByAccount(owner).first().firstOrNull { it.attributeType.id == shadowType }?.let {
                    NewAttribute(shadowType, it.value)
                }
            }
        val named = intent.copy(name = desired, attributes = intent.attributes + tag + listOfNotNull(shadowTag))
        val resolvedName = uniqueName(desired, named, byName)
        val id = createAccount(named, resolvedName)
        byName[resolvedName] = id
        nameById[id] = resolvedName
        byAttr[tag.typeId to tag.value] = id
        return ResolvedFeeAccount(id, created = true)
    }

    /**
     * A display name no other account holds, so the `account.name` UNIQUE constraint can never reject a
     * create/rename. Identity still decides WHICH account an intent matches (see [matchAccount]) — this
     * only disambiguates the display name, so genuinely distinct accounts that share a real-world name
     * (e.g. five different people all named "Nikolay Metchev", two banks both trading as "Payward Ltd.")
     * stay separate and readable rather than colliding. Shape matches the sibling-collision convention
     * already used for Monzo-style accounts: "Name (1234)" (see `ApiSessionImportService.displayName`).
     *
     * [selfId] excludes the intent's own current account from the collision check (for [adoptAccount]
     * renaming an account to a name it may already hold, or re-resolving to a name it already suffixed on
     * an earlier import) so re-imports don't grow an extra suffix each time.
     */
    private fun uniqueName(
        desired: String,
        intent: ImportAccountIntent,
        byName: Map<String, AccountId>,
        selfId: AccountId? = null,
    ): String {
        fun free(candidate: String) = byName[candidate].let { it == null || it == selfId }
        if (free(desired)) return desired
        discriminatorFor(intent)?.let { discriminator ->
            val candidate = "$desired ($discriminator)"
            if (free(candidate)) return candidate
        }
        // Last resort: no identity attribute distinguishes this intent, or even the discriminated name is
        // taken. An incrementing counter always terminates, so the UNIQUE insert can never fail — but it is
        // the only branch whose outcome depends on import order (which duplicate got the counter suffix);
        // every duplicate cluster seen in practice (bank accounts, wallets, exchange external ids) resolves
        // via the identity branch above instead.
        return generateSequence(2) { it + 1 }.map { "$desired ($it)" }.first(::free)
    }

    /**
     * An identity-derived discriminator for [intent], preferring a bank account number (last 4 digits,
     * matching how banks themselves distinguish same-named accounts) and falling back to the intent's own
     * match value (covers a wallet address or an exchange/provider external id — the last 6 characters is
     * enough to disambiguate without making the name unreadably long).
     */
    private fun discriminatorFor(intent: ImportAccountIntent): String? {
        // Take the account number from the first group that yields a COMPLETE bank key, so an account with
        // several identities gets a stable discriminator instead of one that depends on attribute order.
        val accountNumberDiscriminator =
            intentBankKeys(intent)
                .firstOrNull()
                ?.substringAfter('|')
                ?.takeLast(4)
        if (accountNumberDiscriminator != null) return accountNumberDiscriminator
        return (intent.match as? AccountMatchKey.ByExternalId)?.value?.takeLast(6)
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
        batchCreatedByName: Map<String, AccountId>,
        claimedAccountIds: Set<AccountId>,
    ): AccountMatch? {
        val primary =
            when (val match = intent.match) {
                is AccountMatchKey.ByName -> byName[match.name]
                is AccountMatchKey.ByExternalId -> byAttr[match.typeId to match.value]
                is AccountMatchKey.ByBuiltInType -> byAttr[match.typeId to match.value]
                is AccountMatchKey.ByPersonalCounterparty -> byPersonalKey[match.key]
                is AccountMatchKey.AlwaysCreate -> null
                is AccountMatchKey.FeeAccountOf -> error("A fee account is resolved by resolveFeeAccount")
            }
        if (primary != null) return AccountMatch(primary, adopted = false)
        val bankKeys = intentBankKeys(intent)
        // Fallback: an intent that carries bank details (sort code + account number attributes) but didn't
        // match by its primary key adopts a pre-existing account for the same real bank account — e.g. a
        // source account adopting a counterparty another provider created, keeping imports order-independent.
        // The matched account is re-pointed (renamed + the intent's own attributes added) so this provider
        // resolves it by id next time, while its bank attributes keep the other provider matching it.
        bankKeys.firstNotNullOfOrNull { byPersonalKey[it] }?.let { return AccountMatch(it, adopted = true) }
        // An own/source account intent with no bank-identity match yet may still adopt a pre-existing
        // account of the exact same name that carries no identity attributes of its own (an "unclaimed"
        // account — e.g. one created ahead of time by hand, or by the import-directory auto-create-by-name
        // feature). Without this, the first real import for that account forks a duplicate instead of
        // filling in the identity/owner attributes the user's placeholder was waiting for. Scoped to
        // adoptOnBankMatch (own/source) intents only — a COUNTERPARTY must never merge into an unrelated
        // pre-existing account just because the name coincides.
        if (intent.adoptOnBankMatch) {
            intent.name
                ?.let { byName[it] }
                ?.takeIf { it !in claimedAccountIds }
                ?.let { return AccountMatch(it, adopted = true) }
        }
        // Last resort for an intent with no bank identity — a merchant/conduit counterparty rather than a
        // real bank account: reuse an account of the same name created earlier IN THIS BATCH. A provider
        // routinely mints several external ids for one merchant (Monzo issued two for "Curve"), so without
        // this the second id forks another same-named account. Scoped to batch-created names — never a
        // pre-existing account — so an own/source account can't merge into an unrelated account that
        // merely shares its name. Accounts that carry bank details are excluded too: two people can share
        // a name while owning different accounts.
        if (bankKeys.isEmpty() && intent.match !is AccountMatchKey.AlwaysCreate) {
            intent.name?.let { batchCreatedByName[it] }?.let { return AccountMatch(it, adopted = false) }
        }
        return null
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
            val resolvedName = uniqueName(intentName, intent, byName, selfId = id)
            accountRepository.updateAccount(existing.copy(name = resolvedName), intent.source)
            byName.remove(existing.name)
            byName[resolvedName] = id
        }
        val currentAttrs = accountAttributeRepository.getByAccount(id).first()
        val storedGroupKey = storedGroupKeyResolver(intent.attributes, currentAttrs)
        for (attr in intent.attributes) {
            val present = currentAttrs.any { it.attributeType.id == attr.typeId && it.value == attr.value }
            if (!present) {
                // Upsert rather than insert-and-swallow: the (type, group) slot may already hold a
                // different value, and dropping the write there would silently lose this provider's
                // identity for the account.
                accountAttributeRepository.upsertInCreationMode(id, attr.typeId, attr.value, storedGroupKey(attr))
                byAttr.getOrPut(attr.typeId to attr.value) { id }
            }
        }
    }

    /**
     * Builds the function that maps each of an intent's attributes to the `group_key` it is stored under.
     *
     * Stored group keys are opaque UUIDs. The intent carries a transient grouping tag (the natural
     * `personalCounterpartyKey`) that only binds an identity's attributes together within the batch; this
     * translates that tag to a persisted UUID, minting one for a new group and reusing an existing group's
     * UUID when the incoming identity matches an existing one BY DERIVED VALUE. Matching by value (not by
     * key string) is what keeps a re-import idempotent and lets two independently-created accounts be
     * merged without duplicating a shared identity.
     *
     * Ungrouped identity attributes (e.g. a provider external id) are resolved per attribute: one whose
     * ungrouped slot already holds a DIFFERENT value is moved into a group of its own rather than
     * overwriting, so two providers that each know the account by a different external id both survive.
     */
    private fun storedGroupKeyResolver(
        intentAttributes: List<NewAttribute>,
        existingAttrs: List<AccountAttribute>,
    ): (NewAttribute) -> String {
        val existingGroups = existingAttrs.filter { it.groupKey.isNotEmpty() }.groupBy { it.groupKey }
        val tagToStored =
            intentAttributes
                .filter { it.groupKey.isNotEmpty() }
                .groupBy { it.groupKey }
                .keys
                .associateWith { tag ->
                    val incomingKey =
                        bankKeysFrom(
                            intentAttributes
                                .filter { it.groupKey == tag }
                                .map { AttributeSlot(it.typeId.id, it.value, it.groupKey) },
                        ).firstOrNull()
                    val reuse =
                        incomingKey?.let {
                            existingGroups.entries
                                .firstOrNull { (key, rows) ->
                                    bankKeysFrom(rows.map { AttributeSlot(it.attributeType.id.id, it.value, key) })
                                        .firstOrNull() == incomingKey
                                }?.key
                        }
                    reuse ?: Uuid.random().toString()
                }
        val identityTypeIds = IDENTITY_ATTR_TYPE_IDS.map { it.id }
        return { attr ->
            if (attr.groupKey.isNotEmpty()) {
                tagToStored.getValue(attr.groupKey)
            } else {
                val occupied =
                    existingAttrs.any {
                        it.groupKey.isEmpty() && it.attributeType.id == attr.typeId && it.value != attr.value
                    }
                if (occupied && attr.typeId.id in identityTypeIds) Uuid.random().toString() else ""
            }
        }
    }

    /**
     * Every personal-counterparty bank key this intent carries — one per attribute group holding both a
     * sort code and an account number, so an intent describing an account with two bank identities yields
     * both. Derived from the attribute values, never from the group key, so a stale group key is harmless.
     */
    private fun intentBankKeys(intent: ImportAccountIntent): List<String> =
        bankKeysFrom(intent.attributes.map { AttributeSlot(it.typeId.id, it.value, it.groupKey) })

    private suspend fun createAccount(
        intent: ImportAccountIntent,
        name: String,
    ): AccountId {
        val newId =
            accountRepository.createAccount(
                Account(
                    id = AccountId(0),
                    name = name,
                    openingDate = requireNotNull(intent.openingDate),
                    categoryId = intent.categoryId,
                ),
                intent.source,
            )
        val storedGroupKey = storedGroupKeyResolver(intent.attributes, existingAttrs = emptyList())
        intent.attributes.forEach { attr ->
            accountAttributeRepository.insertInCreationMode(newId, attr.typeId, attr.value, storedGroupKey(attr))
        }
        return newId
    }

    private fun indexNewAccount(
        intent: ImportAccountIntent,
        id: AccountId,
        name: String,
        byName: MutableMap<String, AccountId>,
        byAttr: MutableMap<Pair<AttributeTypeId, String>, AccountId>,
        byPersonalKey: MutableMap<String, AccountId>,
    ) {
        byName.getOrPut(name) { id }
        intent.attributes.forEach { attr -> byAttr.getOrPut(attr.typeId to attr.value) { id } }
        when (val match = intent.match) {
            is AccountMatchKey.ByPersonalCounterparty -> byPersonalKey.getOrPut(match.key) { id }
            else -> Unit
        }
        // Register every bank identity the new account carries (from its sort/account attribute groups) so
        // a later intent in this batch sharing any of them merges into it instead of creating a duplicate.
        intentBankKeys(intent).forEach { key -> byPersonalKey.getOrPut(key) { id } }
    }

    /**
     * Builds an index of existing accounts by (attributeTypeId, value), but only for the attribute
     * types actually referenced by attribute-based match keys in this batch — so a name-only batch
     * (CSV/QIF) does not pay to scan every account's attributes.
     */
    private suspend fun buildExistingAccountAttrIndex(intents: List<ImportAccountIntent>): ExistingAccountAttrIndex {
        val relevantTypeIds =
            intents
                .mapNotNull { intent ->
                    when (val m = intent.match) {
                        is AccountMatchKey.ByExternalId -> m.typeId
                        is AccountMatchKey.ByBuiltInType -> m.typeId
                        is AccountMatchKey.FeeAccountOf -> FEE_ACCOUNT_FOR_TYPE
                        else -> null
                    }
                }.toSet()
        // An own/source account intent (adoptOnBankMatch) with no bank-identity match may still adopt a
        // pre-existing SAME-NAMED account that carries none of these identity attributes (see
        // [matchAccount]'s "unclaimed" fallback) — so the per-account attribute scan below must also run
        // whenever such an intent is present, even if this batch's own relevantTypeIds is otherwise empty.
        if (relevantTypeIds.isEmpty() && intents.none { it.adoptOnBankMatch }) {
            return ExistingAccountAttrIndex(mutableMapOf(), mutableSetOf())
        }

        val index = mutableMapOf<Pair<AttributeTypeId, String>, AccountId>()
        val claimed = mutableSetOf<AccountId>()
        // One query over all account attributes rather than a per-account read: this used to be an N+1
        // over the whole account table, which dominated the "Resolving accounts" phase on a large database.
        // getAll() is ordered by (type, account_id), so getOrPut still keeps the lowest-id account for a
        // given (type, value) — the same winner the per-account scan produced.
        for (attr in accountAttributeRepository.getAll().first()) {
            if (attr.attributeType.id in relevantTypeIds) {
                index.getOrPut(attr.attributeType.id to attr.value) { attr.accountId }
            }
            if (attr.attributeType.id in IDENTITY_ATTR_TYPE_IDS) {
                claimed += attr.accountId
            }
        }
        return ExistingAccountAttrIndex(index, claimed)
    }

    /**
     * [byAttr] indexes existing accounts by (relevant attribute type, value) for primary-key matching.
     * [claimedAccountIds] is every existing account carrying at least one identity attribute
     * ([IDENTITY_ATTR_TYPE_IDS]) — an account NOT in this set is "unclaimed": it carries no signal tying
     * it to a specific real bank account/counterparty, so an own/source intent may freely adopt it by
     * name (see [matchAccount]).
     */
    private class ExistingAccountAttrIndex(
        val byAttr: MutableMap<Pair<AttributeTypeId, String>, AccountId>,
        val claimedAccountIds: MutableSet<AccountId>,
    )

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
                intents.any { intentBankKeys(it).isNotEmpty() }
        if (!hasPersonalKeys) return mutableMapOf()

        val index = mutableMapOf<String, AccountId>()
        // One query for every account's attributes rather than a per-account flow read: this used to be an
        // N+1 over the whole account table on every import carrying a bank identity.
        for ((accountId, attrs) in accountAttributeRepository.getAll().first().groupBy { it.accountId }) {
            // Index EVERY identity: an account that owns two bank identities must be reachable by both, or
            // the second one silently forks a duplicate account on the next import.
            existingBankKeys(attrs).forEach { key -> index.getOrPut(key) { accountId } }
        }
        return index
    }

    /** Every bank key an existing account's stored [attrs] resolve to, best source first. */
    private fun existingBankKeys(attrs: List<AccountAttribute>): List<String> {
        val slots = attrs.map { AttributeSlot(it.attributeType.id.id, it.value, it.groupKey) }
        val grouped = bankKeysFrom(slots)
        if (grouped.isNotEmpty()) return grouped
        // Loose pairing: an account whose sort code and account number ended up in different groups (a
        // half-finished hand edit) would otherwise index nothing. Only when there is exactly one of each
        // across the whole account, so this can never invent a pair.
        bankKeysFrom(slots.map { it.copy(groupKey = "") }).singleOrNull()?.let { return listOf(it) }
        // Fallback: an account whose only bank identity is a synthetic "bank:<sort>:<account>" external-id
        // (created before its sort/account were persisted as attributes).
        val externalIdTypeId = AttributeTypeId(WellKnownIds.ACCOUNT_EXTERNAL_ID_ATTR_TYPE_ID)
        return attrs
            .firstOrNull { it.attributeType.id == externalIdTypeId }
            ?.value
            ?.let(::bankKeyFromExternalId)
            ?.let { listOf(it) }
            .orEmpty()
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
                            ?: match.nameKeyFallback?.let { byNameKey[it] }?.also { matchedId ->
                                if (byAttr[match.typeId to match.value] == null) {
                                    runCatching {
                                        personAttributeRepository.insertInCreationMode(matchedId, match.typeId, match.value)
                                    }
                                    byAttr[match.typeId to match.value] = matchedId
                                }
                            }
                    }

                    is PersonMatchKey.ByNameKey -> {
                        byNameKey[match.nameKey]
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

    private suspend fun createOwnerships(
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

    // endregion

    // region Transfers

    /**
     * Existing records of the batch's own source (see [ImportDeduper]'s `ownSourceLegs`): what its CSV files created on
     * an earlier import, and the other lines of the statements it covers. An account books a movement once, on one
     * date, so every export of its statements records it at that time; a record carrying the batch's unique identifier
     * ([ExistingTransferInfo.uniqueKey]), booked from the statement of an account the batch's rows are statements of,
     * at a time and amount none of those rows records, is another line of that account's statements (an equal purchase
     * two days earlier, posted onto the next month's card statement), never another source's record of a row's
     * movement. Another export of the same statement still reconciles, as does a second account's export of a
     * transfer between the two: its lines are that account's statement.
     */
    private suspend fun ownSourceLegs(
        transfers: List<ImportTransfer>,
        existing: List<ExistingTransferInfo>,
    ): Set<TransferId> {
        val ownFiles =
            transfers
                .mapNotNullTo(mutableSetOf()) { (it.source as? Source.Csv)?.importId }
                .flatMapTo(mutableSetOf()) { csvImportRepository.getTransferIdsCreatedByImport(it) }
        val statementLines =
            transfers.mapNotNullTo(mutableSetOf()) { transfer ->
                (transfer.source as? Source.Csv)?.statementAccountId?.let { StatementLine(it, transfer.timestamp, transfer.amount) }
            }
        val identified = existing.filter { it.uniqueKey.isNotEmpty() }.associateBy { it.transferId }
        if (statementLines.isEmpty() || identified.isEmpty()) return ownFiles
        val statementAccounts = statementLines.mapTo(mutableSetOf()) { it.account }
        val otherLines =
            csvImportRepository
                .getStatementAccounts(identified.keys)
                .filter { (id, account) ->
                    val transfer = identified.getValue(id).transfer
                    account in statementAccounts && StatementLine(account, transfer.timestamp, transfer.amount) !in statementLines
                }.keys
        return ownFiles + otherLines
    }

    /** One line of [account]'s statement: what it booked, and when. */
    private data class StatementLine(
        val account: AccountId,
        val timestamp: Instant?,
        val amount: Money?,
    )

    /**
     * The relationships touching [existing], loaded once for the deduper. Among them: the `reconciled`
     * links whose related end another record already reconciled against (a leg only ever stands for one movement,
     * so a second row must not be excused against it — that would drop a genuine movement from the
     * balances), and the `pass-through` links that chain a conduit's legs together.
     */
    private suspend fun existingRelationships(existing: List<ExistingTransferInfo>): List<Relationship> {
        val repository = relationshipRepository ?: return emptyList()
        if (existing.isEmpty()) return emptyList()
        return repository.getByTransactions(existing.map { it.transferId })
    }

    /** The relationships of the [otherSideLegs] that [existing] (whose own are read already) leaves out. */
    private suspend fun otherSideRelationships(
        otherSideLegs: List<Transfer>,
        existing: List<ExistingTransferInfo>,
    ): List<Relationship> {
        val repository = relationshipRepository ?: return emptyList()
        val loaded = existing.mapTo(mutableSetOf()) { it.transferId }
        val ids = otherSideLegs.map { it.id }.filterNot { it in loaded }
        return if (ids.isEmpty()) emptyList() else repository.getByTransactions(ids)
    }

    /**
     * For every existing leg that a `pass-through` relationship continues, the legs after it along its
     * chain (main -> leg 1 -> leg 2 …), in order — see [ImportDeduper]'s `passThroughOnward`. A chain's
     * onward legs start at the conduit the batch's own legs touch, so they are almost always among
     * [existing]; any that are not are fetched by id.
     */
    private suspend fun passThroughOnward(
        existing: List<ExistingTransferInfo>,
        relationships: List<Relationship>,
    ): Map<TransferId, List<Transfer>> {
        val next =
            relationships
                .filter { it.relationshipType.id.id == WellKnownIds.PASS_THROUGH_RELATIONSHIP_TYPE_ID }
                .mapNotNull { it.transferPair }
                .toMap()
        if (next.isEmpty()) return emptyMap()
        val known = existing.associate { it.transferId to it.transfer }
        val missing = next.values.filterNot { it in known }
        val transfers = if (missing.isEmpty()) known else known + transactionRepository.getTransactionsByIds(missing)
        return next.keys.associateWith { start ->
            buildList {
                var id = next[start]
                // A chain is a handful of hops; the bound only guards against a malformed cycle.
                while (id != null && size < MAX_PASS_THROUGH_CHAIN_LEGS) {
                    add(transfers[id] ?: break)
                    id = next[id]
                }
            }
        }
    }

    /**
     * A [TradeReconciler] over the trades the batch's own trades could be duplicating, or null when the
     * batch opts out, has no trades, or nothing exists to match. The window is loaded once and widened
     * by the policy's tolerance, mirroring [loadExisting]'s slack: an existing trade stamped just
     * outside the batch's own span is exactly the one a second-vs-millisecond disagreement produces.
     */
    private suspend fun buildTradeReconciler(batch: ImportBatch): TradeReconciler? {
        val policy = batch.tradeDedupePolicy as? TradeDedupePolicy.Fuzzy ?: return null
        val creates = batch.trades.creates().filter { it.timestamp != null }
        if (creates.isEmpty()) return null
        val accountIds = creates.flatMap { listOfNotNull(it.fromAccountId, it.toAccountId) }.toSet()
        if (accountIds.isEmpty()) return null
        val minTs = creates.minOf { requireNotNull(it.timestamp) } - policy.window
        val maxTs = creates.maxOf { requireNotNull(it.timestamp) } + policy.window
        val existing = tradeRepository.getTradesByAccountsAndDateRange(accountIds, minTs, maxTs)
        return if (existing.isEmpty()) null else TradeReconciler(policy, existing, creates)
    }

    /**
     * A [ConversionTradeReconciler] over the conversions another source already booked as transfers
     * that the batch's own trades could be duplicating, or null when the batch does not name a
     * conversion relationship type, has no trades an order does not claim, or nothing links up.
     *
     * Every step is a narrowing: the window is the policy's, the accounts are the ones the batch's
     * trades debit, and only a transfer that leaves one of those accounts *and* is the primary end of a
     * relationship of the named type is a conversion debit leg. The paired credit legs are loaded
     * only for the asset they received - their amount is not comparable (see
     * [ConversionTradeReconciler]).
     */
    private suspend fun buildConversionTradeReconciler(
        batch: ImportBatch,
        orderLinkedTradeKeys: Set<LocalTradeKey>,
    ): ConversionTradeReconciler? {
        val policy = batch.tradeDedupePolicy as? TradeDedupePolicy.Fuzzy ?: return null
        val typeName = policy.conversionRelationshipTypeName ?: return null
        val relationships = relationshipRepository ?: return null
        val creates =
            batch.trades
                .creates()
                .filter { it.timestamp != null && it.fromAccountId != null && it.key !in orderLinkedTradeKeys }
        if (creates.isEmpty()) return null
        val accountIds = creates.mapNotNullTo(mutableSetOf()) { it.fromAccountId }
        val minTs = creates.minOf { requireNotNull(it.timestamp) } - policy.window
        val maxTs = creates.maxOf { requireNotNull(it.timestamp) } + policy.window
        val typeId = relationshipTypeRepository.getByName(typeName).first()?.id ?: return null

        val candidates =
            accountIds
                .flatMap { transactionRepository.getTransactionsByAccountAndDateRange(it, minTs, maxTs).first() }
                .distinctBy { it.id }
                // A conversion's debit leg is the one the asset LEFT the owner account on; the credit
                // leg runs the other way and is matched through the relationship, never directly.
                .filter { it.sourceAccountId in accountIds }
        if (candidates.isEmpty()) return null

        val creditIdByDebitId =
            relationships
                .getByTransactions(candidates.map { it.id })
                .filter { it.relationshipType.id == typeId }
                .mapNotNull { it.transferPair }
                .toMap()
        if (creditIdByDebitId.isEmpty()) return null
        val creditLegs = transactionRepository.getTransactionsByIds(creditIdByDebitId.values.toSet())

        val legs =
            candidates.mapNotNull { debit ->
                val creditId = creditIdByDebitId[debit.id] ?: return@mapNotNull null
                val credit = creditLegs[creditId] ?: return@mapNotNull null
                ExistingConversionLeg(
                    debitTransferId = debit.id,
                    accountId = debit.sourceAccountId,
                    amount = debit.amount,
                    timestamp = debit.timestamp,
                    creditAssetId = credit.amount.asset.id,
                )
            }
        return if (legs.isEmpty()) null else ConversionTradeReconciler(policy.window, legs, creates)
    }

    /**
     * The locations a business-day window of [policy] counts on: every account's `location` attribute, the default
     * location and the calendars they name. A policy with clock-time windows only reads none of them.
     */
    private suspend fun settlementCalendars(policy: DedupePolicy): SettlementCalendars {
        val repository = holidayCalendarRepository
        if (repository == null || policy.windows().none { it is SettlementWindow.BusinessDays }) return SettlementCalendars.WEEKENDS_ONLY
        val locations =
            accountAttributeRepository
                .getByType(AttributeTypeId(WellKnownIds.ACCOUNT_LOCATION_ATTR_TYPE_ID))
                .first()
                .associate { it.accountId to it.value }
        return SettlementCalendars(locations, settingsRepository.getDefaultLocation().first(), repository.getAll().first())
    }

    /** The candidates for [ImportDeduper]'s other-side match; a reconciliation source's data stays isolated. */
    private suspend fun otherSideLegs(
        transfers: List<ImportTransfer>,
        batch: ImportBatch,
        calendars: SettlementCalendars,
    ): List<Transfer> {
        if (batch.shadowSource != null) return emptyList()
        val exclusionTypeId = batch.dedupePolicy.placeholderExclusionTypeId() ?: return emptyList()
        val timestamps = transfers.mapNotNull { it.timestamp }
        if (timestamps.isEmpty()) return emptyList()
        val range = batch.dedupePolicy.windows().reach(timestamps.min()..timestamps.max(), calendars.strictest, times = 1)
        return transactionRepository.getOtherSideCandidates(
            transfers.mapNotNullTo(mutableSetOf()) { it.amount },
            range.start,
            range.endInclusive,
            exclusionTypeId,
        )
    }

    private suspend fun loadExisting(
        transfers: List<ImportTransfer>,
        batch: ImportBatch,
        calendars: SettlementCalendars,
    ): List<ExistingTransferInfo> {
        if (transfers.isEmpty()) return emptyList()

        val accountIds =
            transfers
                .flatMap {
                    // Include any funding-card reconcile account: the funding leg lives on it (and on the
                    // conduit), so load its history even when the batch never moves money to/from it.
                    listOfNotNull(requireId(it.fromAccount), requireId(it.toAccount), it.reconcileFundingAccountId) +
                        // Every conduit of a pass-through chain: an inner one (PayPal behind Curve) is never the
                        // main transfer's account, yet its own statement's legs are what the chain reconciles with.
                        it.passThrough
                            ?.conduits
                            .orEmpty()
                            .map(::requireId)
                }.toSet()

        val rawTransfers =
            when (batch.dedupePolicy) {
                is DedupePolicy.FuzzyAllFields -> {
                    val policy = batch.dedupePolicy as DedupePolicy.FuzzyAllFields
                    // Widen the load window by the dedupe slack so an existing transfer whose timestamp
                    // sits just outside the incoming batch's own span — but still within the fuzzy date
                    // tolerance or the cross-source reconcile window — is available as a candidate. This
                    // matters for date-only exports (e.g. Curve stamps a date that parses to noon) where
                    // the counterpart card leg lands hours later the same day, or a day or two later.
                    val range =
                        policy.windows().reach(
                            transfers.minOf { requireNotNull(it.timestamp) }..transfers.maxOf { requireNotNull(it.timestamp) },
                            calendars.strictest,
                            times = 1,
                        )
                    accountIds
                        .flatMap {
                            transactionRepository.getTransactionsByAccountAndDateRange(it, range.start, range.endInclusive).first()
                        }.distinctBy { it.id }
                }

                is DedupePolicy.UniqueIdentifier -> {
                    loadAroundBatch(transfers, accountIds.toList(), batch.dedupePolicy.windows(), calendars)
                }

                is DedupePolicy.ApiMultiKey -> {
                    // The own account may be the source or the target depending on direction; the app
                    // accounts bridged for internal-transfer reconciliation hold legs the batch never touches.
                    val policy = batch.dedupePolicy as DedupePolicy.ApiMultiKey
                    loadAroundBatch(
                        transfers,
                        (accountIds + policy.internalTransferBridges.map { it.appAccountId }).toList(),
                        policy.windows(),
                        calendars,
                    )
                }

                is DedupePolicy.None -> {
                    emptyList()
                }
            }
        // A leg another source could only date approximately may truly sit far outside the date range
        // above, so load those few legs on the batch's accounts whatever their date.
        val approximateLegs =
            if (batch.dedupePolicy is DedupePolicy.FuzzyAllFields) {
                transactionRepository.getTransactionsByAccountsWithAttributeType(
                    accountIds,
                    WellKnownIds.TIMESTAMP_APPROXIMATE_ATTR_TYPE_NAME,
                )
            } else {
                emptyList()
            }

        val uniqueKeyExtractor = batch.uniqueKeyExtractor
        val apiIdExtractor = batch.apiIdExtractor
        return (rawTransfers + approximateLegs).distinctBy { it.id }.map { transfer ->
            ExistingTransferInfo(
                transferId = transfer.id,
                transfer = transfer,
                attributes = transfer.attributes.associate { it.attributeType.id to it.value },
                uniqueKey = uniqueKeyExtractor?.extract(transfer).orEmpty(),
                apiId = apiIdExtractor?.extract(transfer),
                approximateUntil =
                    transfer.attributes
                        .firstOrNull { it.attributeType.name == WellKnownIds.TIMESTAMP_APPROXIMATE_ATTR_TYPE_NAME }
                        ?.value
                        ?.toLongOrNull()
                        ?.let { Instant.fromEpochMilliseconds(it) },
            )
        }
    }

    /**
     * The existing transfers on [accountIds] a unique-id policy can match the batch against, instead of each
     * account's whole history: everything near the batch in time, plus the few records that may match from
     * any distance — those carrying one of the batch's own ids, and those dated only approximately.
     *
     * Every other rule pairs an existing leg with a batch row inside one of the policy's [windows], or (the
     * split-conduit pass) with an existing placeholder that is itself inside the window of a batch row, so
     * widening by every window twice covers each one. A leg outside that cannot match anything; loading it changed
     * nothing but the cost, which on a large database was most of a re-import.
     *
     * The result keeps the order the full-history load gave (account by account, newest first), since the
     * deduper's indexes keep the first or last of several candidates.
     */
    private suspend fun loadAroundBatch(
        transfers: List<ImportTransfer>,
        accountIds: List<AccountId>,
        windows: List<SettlementWindow>,
        calendars: SettlementCalendars,
    ): List<Transfer> {
        val timestamps = transfers.map { it.timestamp ?: return loadFullHistory(accountIds) }
        // An approximately-dated row may have happened as late as its approximateUntil.
        val latest = transfers.maxOf { maxOf(requireNotNull(it.timestamp), it.approximateUntil ?: Instant.DISTANT_PAST) }
        val range = windows.reach(timestamps.min()..latest, calendars.strictest, times = 2)
        val from = range.start
        val until = range.endInclusive
        val ids = transfers.flatMap { it.uniqueKey?.values.orEmpty() + listOfNotNull(it.apiId) }
        val loaded =
            accountIds.flatMap { transactionRepository.getTransactionsByAccountAndDateRange(it, from, until).first() } +
                transactionRepository.getTransactionsByAccountsWithAttributeValues(accountIds, ids) +
                transactionRepository.getTransactionsByAccountsWithAttributeType(
                    accountIds,
                    WellKnownIds.TIMESTAMP_APPROXIMATE_ATTR_TYPE_NAME,
                )
        val byAccount = mutableMapOf<AccountId, MutableList<Transfer>>()
        for (transfer in loaded.distinctBy { it.id }.sortedByDescending { it.timestamp }) {
            byAccount.getOrPut(transfer.sourceAccountId) { mutableListOf() } += transfer
            if (transfer.targetAccountId != transfer.sourceAccountId) {
                byAccount.getOrPut(transfer.targetAccountId) { mutableListOf() } += transfer
            }
        }
        return accountIds.flatMap { byAccount[it].orEmpty() }.distinctBy { it.id }
    }

    private suspend fun loadFullHistory(accountIds: List<AccountId>): List<Transfer> =
        accountIds.flatMap { transactionRepository.getTransactionsByAccount(it).first() }.distinctBy { it.id }

    /** Every window within which a rule of this policy pairs an incoming row with an existing leg. */
    private fun DedupePolicy.windows(): List<SettlementWindow> =
        when (this) {
            is DedupePolicy.UniqueIdentifier -> {
                listOfNotNull(reconcileWindow, unidentifiedCounterpartyWindow)
            }

            is DedupePolicy.ApiMultiKey -> {
                listOfNotNull(reconcileWindow, unidentifiedCounterpartyWindow, internalTransferWindow)
            }

            is DedupePolicy.FuzzyAllFields -> {
                listOfNotNull(SettlementWindow.Elapsed(dateTolerance.inWholeSeconds), reconcileWindow, unidentifiedCounterpartyWindow)
            }

            DedupePolicy.None -> {
                emptyList()
            }
        }

    /** [range] widened [times] over by each of these windows: every instant a match could carry. */
    private fun List<SettlementWindow>.reach(
        range: ClosedRange<Instant>,
        calendar: BusinessDayCalendar,
        times: Int,
    ): ClosedRange<Instant> =
        fold(range) { reached, window ->
            val widened = (1..times).fold(range) { r, _ -> window.widen(r.start, r.endInclusive, calendar) }
            minOf(reached.start, widened.start)..maxOf(reached.endInclusive, widened.endInclusive)
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
        onProgress: (suspend (Progress) -> Unit)?,
        engineOwnedTypeId: AttributeTypeId?,
        createdLegs: MutableList<Pair<ExpandedTransfer, TransferId>>,
    ): List<TransferId> {
        if (toImport.isEmpty() && toUpdate.isEmpty()) return emptyList()

        val effectiveBatchSize = batchSize.coerceAtLeast(1)
        if (toImport.isEmpty()) {
            // No creates, only updates: apply them in their own transaction.
            val (updates, updateSources) = buildUpdates(toUpdate, engineOwnedTypeId)
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

        // Resolve (get-or-create) every relationship type named by a batch relationship once, so the
        // expander (pure) can look ids up synchronously.
        val batchRelTypeIds: Map<String, RelationshipTypeId> =
            toImport
                .flatMap { it.transfer.batchRelationships }
                .map { it.typeName }
                .distinct()
                .associateWith { relationshipTypeRepository.getOrCreate(it) }
        val expanded = ObservationExpander.expandTransfers(toImport.toExpansionRows(), batchRelTypeIds)

        val total = toImport.size
        val createdIds = mutableListOf<TransferId>()
        // Real ids of the legs already-written chunks created, so a link whose target landed in an earlier
        // chunk (its temp id no longer resolvable) still links by real id.
        val priorIds = mutableMapOf<ObservationKey, TransferId>()
        var written = 0
        // Fast-path the common single-transaction case (default batchSize) so it doesn't copy the whole list.
        val chunks = if (effectiveBatchSize >= expanded.size) listOf(expanded) else expanded.chunked(effectiveBatchSize)
        chunks.forEachIndexed { index, chunk ->
            val payload = buildCreatePayload(chunk, priorIds)
            // Updates ride with the final create chunk (one transaction in the common single-chunk case).
            val (updates, updateSources) =
                if (index == chunks.lastIndex) buildUpdates(toUpdate, engineOwnedTypeId) else emptyList<TransferUpdate>() to emptyList()
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
            chunk.flatMap { it.legs }.forEachIndexed { i, leg ->
                priorIds[leg.key] = allCreatedIds[i]
                createdLegs += leg to allCreatedIds[i]
            }
            written += chunk.size
            onProgress?.invoke(
                Progress(
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

    /** What the expander needs of each row to import: the row and what classification decided about it. */
    private fun List<Classified>.toExpansionRows(): List<ExpansionRow> {
        val owners = map { it.transfer.observationOwner() }
        return map { classified ->
            ExpansionRow(
                transfer = classified.transfer,
                conduitStatement = classified.conduitStatement,
                supersedesLegs = classified.supersededLegs.isNotEmpty(),
                reversals =
                    classified.reversalLinks.mapValues { (_, link) ->
                        val target =
                            when (val reversed = link.target) {
                                is ReversalTarget.Existing -> {
                                    LegLinkTarget.Existing(reversed.id)
                                }

                                is ReversalTarget.BatchRow -> {
                                    LegLinkTarget.Expanded(
                                        ObservationKey(owners[reversed.toImportIndex], ObservationSlot.conduit(reversed.legIndex)),
                                    )
                                }
                            }
                        LegLink(target, link.typeId)
                    },
            )
        }
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

    /**
     * Writes [chunk]'s expanded legs with negative temp ids, so newAttributes/newRelationships can be keyed before
     * real ids exist. A link resolves to a leg of this chunk by its temp id (the repository's in-batch id map is per
     * importTransfers call, so a main and its fee and spend legs stay in one chunk), else to a leg [priorIds] holds
     * from an earlier chunk; one to a leg of a later chunk is dropped.
     */
    private fun buildCreatePayload(
        chunk: List<ExpandedRow>,
        priorIds: Map<ObservationKey, TransferId>,
    ): CreatePayload {
        val legs = chunk.flatMap { it.legs }
        val tempIds = legs.indices.map { TransferId(-(it + 1).toLong()) }
        // Last wins, as the expander's own row-key lookup does.
        val tempIdByKey = legs.zip(tempIds).associate { (leg, id) -> leg.key to id }
        val newAttributes = mutableMapOf<TransferId, List<NewAttribute>>()
        val newRelationships = mutableMapOf<TransferId, List<NewRelationship>>()
        legs.forEachIndexed { i, leg ->
            if (leg.attributes.isNotEmpty()) newAttributes[tempIds[i]] = leg.attributes
            val relationships =
                leg.links.mapNotNull { link ->
                    val target =
                        when (val t = link.target) {
                            is LegLinkTarget.Existing -> t.id
                            is LegLinkTarget.Expanded -> tempIdByKey[t.key] ?: priorIds[t.key]
                        }
                    target?.let { NewRelationship(relatedTransferId = it, typeId = link.typeId) }
                }
            if (relationships.isNotEmpty()) newRelationships[tempIds[i]] = relationships
        }
        val transfers =
            legs.mapIndexed { i, leg ->
                Transfer(
                    id = tempIds[i],
                    timestamp = leg.timestamp,
                    description = leg.description,
                    sourceAccountId = leg.sourceAccountId,
                    targetAccountId = leg.targetAccountId,
                    amount = leg.amount,
                )
            }
        val mainResultIndices = chunk.runningFold(0) { start, row -> start + row.legs.size }.dropLast(1)
        return CreatePayload(transfers, newAttributes, newRelationships, legs.map { it.provenance }, mainResultIndices)
    }

    /**
     * Synthetic UPDATE entries that tag the stale app-side legs of internal-transfer reconciliations
     * excluded-from-balances (the movement is now carried by the rewritten incoming transfer). One per
     * distinct existing transfer; the row fields are preserved and only the exclusion attribute is added.
     */
    private fun internalReconcileExclusions(classified: List<Classified>): List<Classified> =
        classified
            .flatMap { listOfNotNull(it.excludeExisting) + it.supersededLegs }
            .distinctBy { it.transfer.id }
            .map { ex ->
                Classified(
                    transfer =
                        ImportTransfer(
                            source = Source.System,
                            rowKey = ImportRowKey.Manual(ex.transfer.id.id),
                            fromAccount = AccountRef.Existing(ex.transfer.sourceAccountId),
                            toAccount = AccountRef.Existing(ex.transfer.targetAccountId),
                            timestamp = ex.transfer.timestamp,
                            description = ex.transfer.description,
                            amount = ex.transfer.amount,
                            attributes = listOf(NewAttribute(ex.exclusionTypeId, "reconciled")),
                        ),
                    status = ImportStatus.UPDATED,
                    existing = ex.transfer.id,
                )
            }

    private suspend fun buildUpdates(
        toUpdate: List<Classified>,
        engineOwnedTypeId: AttributeTypeId?,
    ): Pair<List<TransferUpdate>, List<Source>> {
        val updates = mutableListOf<TransferUpdate>()
        val orderedUpdateSources = mutableListOf<Source>()
        val excludedTypeId = AttributeTypeId(WellKnownIds.EXCLUDED_ATTR_TYPE_ID)
        // Main transfers whose exclusion the source now asserts (true) or has withdrawn (false).
        val exclusionChanges = mutableMapOf<TransferId, ExclusionChange>()
        for (classified in toUpdate) {
            val existingId = classified.existing ?: continue
            val t = classified.transfer
            val reported = t.attributes.mapTo(mutableSetOf()) { it.typeId }
            val removed = t.ownedAttributes.filterKeys { it !in reported && it != engineOwnedTypeId }
            val source = t.source.forRow(requireNotNull(t.rowKey))
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
                    removedAttributes = removed,
                )
            orderedUpdateSources += source
            if (t.ownedAttributes.containsKey(excludedTypeId)) {
                exclusionChanges[existingId] =
                    ExclusionChange(t.attributes.firstOrNull { it.typeId == excludedTypeId }, t.ownedAttributes[excludedTypeId], source)
            }
        }
        derivedLegExclusionUpdates(exclusionChanges, excludedTypeId, updates.mapTo(mutableSetOf()) { it.transfer.id })
            .forEach { (update, source) ->
                updates += update
                orderedUpdateSources += source
            }
        return updates to orderedUpdateSources
    }

    /**
     * The legs derived from a main transfer — its fee and its pass-through spend legs (C1→C2, …, Cn→merchant,
     * each linked to the next) — follow its exclusion when a re-import changes it, as they do on creation
     * (see buildCreatePayload): a row the source deleted mustn't move balances through them, and one it
     * un-deleted must again. Needs [relationshipRepository]; skipped without it.
     */
    private suspend fun derivedLegExclusionUpdates(
        exclusionChanges: Map<TransferId, ExclusionChange>,
        excludedTypeId: AttributeTypeId,
        alreadyUpdated: Set<TransferId>,
    ): List<Pair<TransferUpdate, Source>> {
        val relationships = relationshipRepository ?: return emptyList()
        if (exclusionChanges.isEmpty()) return emptyList()
        val derivedTypes = setOf(WellKnownIds.FEE_RELATIONSHIP_TYPE_ID, WellKnownIds.PASS_THROUGH_RELATIONSHIP_TYPE_ID)
        // Derived leg -> the main transfer it hangs off, walking fee/pass-through links outwards (primary -> related).
        val mainOf = mutableMapOf<TransferId, TransferId>()
        var frontier: Map<TransferId, TransferId> = exclusionChanges.keys.associateWith { it }
        while (frontier.isNotEmpty()) {
            val next =
                relationships
                    .getByTransactions(frontier.keys)
                    .filter { it.relationshipType.id.id in derivedTypes }
                    .mapNotNull { it.transferPair }
                    .filter { (main, leg) -> main in frontier && leg !in mainOf && leg !in exclusionChanges }
                    .associate { (main, leg) -> leg to frontier.getValue(main) }
            mainOf += next
            frontier = next
        }
        mainOf.keys.removeAll(alreadyUpdated)
        if (mainOf.isEmpty()) return emptyList()
        return transactionRepository.getTransactionsByIds(mainOf.keys).mapNotNull { (legId, leg) ->
            val change = exclusionChanges.getValue(mainOf.getValue(legId))
            val stored = leg.attributes.firstOrNull { it.attributeType.id == excludedTypeId }
            when {
                change.exclusion != null && stored == null -> {
                    TransferUpdate(leg, listOf(change.exclusion)) to change.source
                }

                // Only lift an exclusion the source itself set (see ImportTransfer.ownedAttributes).
                change.exclusion == null && stored != null && change.ownedValues.let { it == null || stored.value in it } -> {
                    TransferUpdate(leg, emptyList(), mapOf(excludedTypeId to change.ownedValues)) to change.source
                }

                else -> {
                    null
                }
            }
        }
    }

    // endregion

    /** A re-imported main transfer's exclusion as its source now reports it (null = not excluded). */
    private data class ExclusionChange(
        val exclusion: NewAttribute?,
        val ownedValues: Set<String>?,
        val source: Source,
    )

    private fun resolveRef(
        ref: AccountRef,
        keyToId: Map<LocalAccountKey, AccountId>,
    ): AccountRef =
        when (ref) {
            is AccountRef.Existing -> {
                ref
            }

            is AccountRef.Local -> {
                AccountRef.Existing(
                    keyToId[ref.key] ?: error("Unresolved account reference: ${ref.key}"),
                )
            }
        }

    /** This main leg as [row] said it, before the deduper rewrote it: its own ends, and the attributes the rewrite dropped. */
    private fun ExpandedTransfer.asSaidBy(row: ImportTransfer): ExpandedTransfer =
        copy(
            sourceAccountId = requireId(row.fromAccount),
            targetAccountId = requireId(row.toAccount),
            attributes = attributes + row.attributes.filter { said -> attributes.none { it.typeId == said.typeId } },
        )

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
        val importDirectoryIds: Map<String, ImportDirectoryId>,
        val apiCredentialIds: Map<String, ApiCredentialId>,
        val apiSessionIds: Map<String, ApiSessionId>,
        val apiRequestIds: Map<String, ApiRequestId>,
        val apiResponseIds: Map<String, ApiResponseId>,
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

    /** Ids of the shadow accounts of reconciliation source [source]. */
    private suspend fun shadowAccountIds(source: String): Set<AccountId> =
        accountAttributeRepository
            .getByType(AttributeTypeId(WellKnownIds.ACCOUNT_RECONCILIATION_SOURCE_ATTR_TYPE_ID))
            .first()
            .filter { it.value == source }
            .mapTo(mutableSetOf()) { it.accountId }

    /**
     * Fails a shadow-only batch (see [ImportBatch.shadowSource]) that names an existing account outside
     * [source]'s shadow accounts — the last line of defence keeping reconciliation data off real accounts.
     * Batch-local account refs are policed after resolution instead.
     */
    private suspend fun requireShadowOnlyReferences(
        batch: ImportBatch,
        source: String,
    ) {
        // Only creates: an UPDATE/DELETE names an existing record by id, which could be a real one.
        val nonCreates =
            batch.transfers.count { it.operation != ImportOperation.CREATE } +
                batch.trades.count { it.operation != ImportOperation.CREATE } +
                batch.accountsToCreate.count { it.operation != ImportOperation.CREATE }
        require(nonCreates == 0) { "Reconciliation import for '$source' may only create records ($nonCreates update/delete intents)" }
        val referenced = mutableSetOf<AccountId>()

        fun add(ref: AccountRef?) {
            if (ref is AccountRef.Existing) referenced += ref.id
        }
        for (t in batch.transfers.creates()) {
            add(t.fromAccount)
            add(t.toAccount)
            t.fee?.let {
                add(it.source)
                add(it.target)
            }
            t.passThrough?.let { pt ->
                pt.conduits.forEach(::add)
                add(pt.merchantTarget)
            }
        }
        for (trade in batch.trades.creates()) {
            trade.fromAccountId?.let(referenced::add)
            trade.toAccountId?.let(referenced::add)
        }
        if (referenced.isEmpty()) return
        val strays = referenced - shadowAccountIds(source)
        require(strays.isEmpty()) { "Reconciliation import for '$source' references non-shadow account(s) $strays" }
    }

    private suspend fun applyReconciliationLinkMutations(mutations: List<ReconciliationLinkMutation>) {
        val repository = requireNotNull(reconciliationLinkRepository) { "Reconciliation links are not supported by this engine" }
        val sourceByShadow =
            accountAttributeRepository
                .getByType(AttributeTypeId(WellKnownIds.ACCOUNT_RECONCILIATION_SOURCE_ATTR_TYPE_ID))
                .first()
                .associate { it.accountId to it.value }
        for (m in mutations) {
            when (m) {
                is ReconciliationLinkMutation.SetLinks -> {
                    require(m.shadowAccountId in sourceByShadow) { "Account ${m.shadowAccountId} is not a shadow account" }
                    val shadowTargets = m.realAccountIds.filter { it in sourceByShadow }
                    require(shadowTargets.isEmpty()) { "Cannot link a shadow account to shadow account(s) $shadowTargets" }
                    repository.setLinks(m.shadowAccountId, m.realAccountIds)
                    // Unlinking a wallet completely is a decision automatic linking must respect;
                    // linking it again withdraws that decision.
                    val declinedTypeId = AttributeTypeId(WellKnownIds.ACCOUNT_RECONCILIATION_AUTO_LINK_DECLINED_ATTR_TYPE_ID)
                    val declined =
                        accountAttributeRepository.getByAccount(m.shadowAccountId).first().filter {
                            it.attributeType.id ==
                                declinedTypeId
                        }
                    when {
                        m.realAccountIds.isEmpty() && declined.isEmpty() -> {
                            accountAttributeRepository.insert(m.shadowAccountId, declinedTypeId, "true")
                        }

                        m.realAccountIds.isNotEmpty() -> {
                            declined.forEach { accountAttributeRepository.delete(it.id) }
                        }
                    }
                }
            }
        }
    }

    /** Brings the file's import records to [mutation]'s hashes, writing only the rows whose hash is new or changed. */
    private suspend fun recordRows(
        records: ImportRecordWriteRepository,
        mutation: CsvImportMutation.RecordRows,
    ) {
        val stored = records.getCsvImportRecords(mutation.id).associateBy { (it.key as ImportRecordKey.CsvRow).rowIndex }
        for ((rowIndex, hash) in mutation.contentHashes) {
            val record = stored[rowIndex]
            when {
                record == null -> records.insertRecord(ImportRecordKey.CsvRow(mutation.id, rowIndex), hash)
                record.contentHash != hash -> records.updateContentHash(record.id, hash)
            }
        }
    }

    // Without the records repository there are no records to hash, so the session is never unchanged and its re-import runs.
    private suspend fun recordItems(mutation: ApiSessionMutation.RecordItems) {
        val records = importRecordRepository ?: return
        for (record in records.getApiSessionRecords(mutation.sessionId)) {
            if (record.contentHash != mutation.contentHash) records.updateContentHash(record.id, mutation.contentHash)
        }
    }

    // What each transfer a person edits holds before the edit; none without the repositories to record overrides in.
    private suspend fun handEditSnapshots(updates: List<ImportTransfer>): Map<TransferId, MaterializedTransaction> {
        val observations = observationRepository ?: return emptyMap()
        if (personOverrideRepository == null) return emptyMap()
        return updates
            .filter { it.source == Source.Manual }
            .mapNotNull { intent -> requireNotNull(intent.existingId).let { id -> observations.getMaterialized(id)?.let { id to it } } }
            .toMap()
    }

    private suspend fun recordHandEdits(before: Map<TransferId, MaterializedTransaction>) {
        val overrides = personOverrideRepository ?: return
        for ((id, was) in before) {
            val now = checkNotNull(observationRepository).getMaterialized(id) ?: continue
            overrides.recordTransferEdit(id, was, now)
        }
    }

    private suspend fun applyConfigMutations(batch: ImportBatch): ConfigOutcome {
        if (batch.reconciliationLinkMutations.isNotEmpty()) applyReconciliationLinkMutations(batch.reconciliationLinkMutations)
        for (m in batch.tradeAttributeMutations) {
            when (m) {
                is TradeAttributeMutation.Set -> tradeRepository.setAttributeByHand(m.tradeId, m.typeId, m.value, Source.Manual)
                is TradeAttributeMutation.Remove -> tradeRepository.setAttributeByHand(m.tradeId, m.typeId, null, Source.Manual)
            }
            val value = (m as? TradeAttributeMutation.Set)?.value
            personOverrideRepository?.setTradeAttributeOverride(m.tradeId, m.typeId.tradeOverride(value))
        }
        for (m in batch.passThroughMutations) {
            when (m) {
                is PassThroughMutation.Create -> passThroughAccountRepository.create(m.account)
                is PassThroughMutation.Update -> passThroughAccountRepository.update(m.account)
                is PassThroughMutation.Delete -> passThroughAccountRepository.delete(m.id)
            }
        }
        for (m in batch.holidayCalendarMutations) {
            val repository = checkNotNull(holidayCalendarRepository) { "This engine has no holiday calendar repository" }
            when (m) {
                is HolidayCalendarMutation.Upsert -> repository.upsert(m.calendar)
                is HolidayCalendarMutation.Delete -> repository.delete(m.code)
            }
        }
        val csvStrategyIds = mutableMapOf<String, CsvImportStrategyId>()
        for (m in batch.csvStrategyMutations) {
            when (m) {
                is CsvStrategyMutation.Create -> {
                    csvStrategyIds.putUnique(m.key, csvImportStrategyRepository.createStrategy(m.strategy, m.source), "CsvStrategy")
                }

                is CsvStrategyMutation.Update -> {
                    val previous = csvImportStrategyRepository.getStrategyById(m.strategy.id).first()
                    require(previous == null || m.strategy.keepsImportSettingsOf(previous)) {
                        "\"${m.strategy.name}\" can't stop setting its source account or currency: the files imported with it re-import with them"
                    }
                    csvImportStrategyRepository.updateStrategy(m.strategy, m.source)
                }

                is CsvStrategyMutation.Delete -> {
                    csvImportStrategyRepository.deleteStrategy(m.id)
                }
            }
        }

        val apiStrategyIds = mutableMapOf<String, ApiImportStrategyId>()
        for (m in batch.apiStrategyMutations) {
            when (m) {
                is ApiStrategyMutation.Create -> {
                    apiStrategyIds.putUnique(m.key, apiImportStrategyRepository.createStrategy(m.strategy, m.source), "ApiStrategy")
                }

                is ApiStrategyMutation.Update -> {
                    apiImportStrategyRepository.updateStrategy(m.strategy, m.source)
                }

                is ApiStrategyMutation.Delete -> {
                    apiImportStrategyRepository.deleteStrategy(m.id)
                }
            }
        }

        val accountMappingIds = mutableMapOf<String, Long>()
        for (m in batch.accountMappingMutations) {
            when (m) {
                is AccountMappingMutation.Create -> {
                    accountMappingIds.putUnique(
                        m.key,
                        accountMappingRepository.createMapping(m.valuePattern, m.accountId, m.strategyId),
                        "AccountMapping",
                    )
                }

                is AccountMappingMutation.CreateBatch -> {
                    accountMappingRepository.createMappings(m.mappings)
                }

                is AccountMappingMutation.Update -> {
                    accountMappingRepository.updateMapping(m.mapping)
                }

                is AccountMappingMutation.Delete -> {
                    accountMappingRepository.deleteMapping(m.id)
                }
            }
        }

        val csvImportIds = mutableMapOf<String, CsvImportId>()
        for (m in batch.csvImportMutations) {
            when (m) {
                is CsvImportMutation.Create -> {
                    csvImportIds.putUnique(
                        m.key,
                        csvImportRepository.createImport(
                            m.fileName,
                            m.headers,
                            m.rows,
                            m.fileChecksum,
                            m.fileLastModified,
                            m.xlsxBytes,
                            m.xlsxWorksheetName,
                            m.pdf,
                        ),
                        "CsvImport",
                    )
                }

                is CsvImportMutation.Restage -> {
                    csvImportRepository.restageImport(m.id, m.headers, m.rows, m.worksheetName, m.pdfRulesHash)
                }

                is CsvImportMutation.Repopulate -> {
                    csvImportRepository.repopulateImport(
                        m.id,
                        m.headers,
                        m.rows,
                        m.fileChecksum,
                        m.fileLastModified,
                        m.carriedRows,
                        m.rowIndexRemap,
                        m.xlsxBytes,
                        m.pdf,
                    )
                }

                is CsvImportMutation.Delete -> {
                    csvImportRepository.deleteImport(m.id)
                }

                is CsvImportMutation.SetIgnored -> {
                    csvImportRepository.setImportIgnored(m.id, m.ignored)
                }

                is CsvImportMutation.ResetToUnimported -> {
                    csvImportRepository.resetToUnimported(m.id)
                }

                is CsvImportMutation.UpdateRowStatuses -> {
                    csvImportRepository.updateRowStatusesBatch(m.id, m.status, m.rowTransferMap)
                }

                is CsvImportMutation.ResetRowStatuses -> {
                    csvImportRepository.resetRowStatuses(m.id, m.rowIndexes)
                }

                is CsvImportMutation.SaveErrors -> {
                    csvImportRepository.saveErrors(m.id, m.errors)
                }

                is CsvImportMutation.ClearError -> {
                    csvImportRepository.clearError(m.id, m.rowIndex)
                }

                is CsvImportMutation.ClearErrors -> {
                    csvImportRepository.clearErrors(m.id, m.rowIndexes)
                }

                is CsvImportMutation.RecordApplication -> {
                    csvImportRepository.recordImportApplication(
                        m.id,
                        m.strategyId,
                        m.strategyName,
                        m.appliedAt,
                        m.accountId,
                        m.currencyId,
                    )
                }

                is CsvImportMutation.RecordRows -> {
                    importRecordRepository?.let { recordRows(it, m) }
                }
            }
        }

        val importDirectoryIds = mutableMapOf<String, ImportDirectoryId>()
        for (m in batch.importDirectoryMutations) {
            when (m) {
                is ImportDirectoryMutation.Create -> {
                    importDirectoryIds.putUnique(
                        m.key,
                        importDirectoryRepository.createDirectory(m.directory, m.source),
                        "ImportDirectory",
                    )
                }

                is ImportDirectoryMutation.Update -> {
                    importDirectoryRepository.updateDirectory(m.directory, m.source)
                }

                is ImportDirectoryMutation.Delete -> {
                    importDirectoryRepository.deleteDirectory(m.id)
                }

                is ImportDirectoryMutation.RecordFileImported -> {
                    importDirectoryRepository.recordFileImported(
                        m.directoryId,
                        m.fileRef,
                        m.fileName,
                        m.lastModified,
                        m.checksum,
                        m.remoteContentHash,
                        m.csvImportId,
                        m.importedAt,
                    )
                }
            }
        }

        val apiCredentialIds = mutableMapOf<String, ApiCredentialId>()
        val apiSessionIds = mutableMapOf<String, ApiSessionId>()
        val apiRequestIds = mutableMapOf<String, ApiRequestId>()
        val apiResponseIds = mutableMapOf<String, ApiResponseId>()
        for (m in batch.apiSessionMutations) {
            when (m) {
                is ApiSessionMutation.EnsureCredential -> {
                    apiCredentialIds.putUnique(
                        m.key,
                        apiSessionRepository.ensureCredential(m.strategyId, m.createdAt),
                        "ApiCredential",
                    )
                }

                is ApiSessionMutation.UpdateCredentialStrategy -> {
                    apiSessionRepository.updateCredentialStrategy(
                        m.credentialId,
                        m.strategyId,
                    )
                }

                is ApiSessionMutation.CreateSession -> {
                    apiSessionIds.putUnique(
                        m.key,
                        apiSessionRepository.createSession(m.deviceId, m.createdAt, expiresAt = null, m.credentialId),
                        "ApiSession",
                    )
                }

                is ApiSessionMutation.InsertRequest -> {
                    apiRequestIds.putUnique(
                        m.key,
                        apiSessionRepository.insertRequest(m.sessionId, m.method, m.url, m.headers),
                        "ApiRequest",
                    )
                }

                is ApiSessionMutation.RecordDownloadCoverage -> {
                    apiSessionRepository.recordDownloadCoverage(m.sessionId, m.endpointKey, m.coversUntil)
                }

                is ApiSessionMutation.InsertResponse -> {
                    apiResponseIds.putUnique(
                        m.key,
                        apiSessionRepository.insertResponse(m.requestId, m.sessionId, m.json),
                        "ApiResponse",
                    )
                }

                is ApiSessionMutation.DeleteSession -> {
                    deleteSessionRecords(m.id)
                    apiSessionRepository.deleteSession(m.id)
                }

                is ApiSessionMutation.InsertResponseTransaction -> {
                    apiSessionRepository.insertResponseTransaction(m.responseId, m.jsonPath, m.state, m.transactionId, m.errorMessage)
                }

                is ApiSessionMutation.InsertResponseTransactions -> {
                    apiSessionRepository.insertResponseTransactions(m.transactions)
                }

                is ApiSessionMutation.DeleteResponseTransactionsBySession -> {
                    apiSessionRepository.deleteResponseTransactionsBySession(m.sessionId)
                }

                is ApiSessionMutation.RecordItems -> {
                    recordItems(m)
                }

                is ApiSessionMutation.ResetToUnimported -> {
                    deleteSessionRecords(m.sessionId)
                    apiSessionRepository.deleteResponseTransactionsBySession(m.sessionId)
                    apiSessionRepository.deleteSessionImports(m.sessionId)
                }

                is ApiSessionMutation.MarkSessionImported -> {
                    apiSessionRepository.markSessionImported(m.id, m.revisionId, m.importedAt, m.importDurationMillis)
                }
            }
        }

        batch.settings?.let { s ->
            s.defaultCurrencyId?.let { settingsRepository.setDefaultCurrencyId(it) }
            s.defaultLocation?.let { settingsRepository.setDefaultLocation(it.code) }
            s.setupWizardCompleted?.let { settingsRepository.setSetupWizardCompleted(it) }
        }

        return ConfigOutcome(
            csvStrategyIds = csvStrategyIds,
            apiStrategyIds = apiStrategyIds,
            accountMappingIds = accountMappingIds,
            csvImportIds = csvImportIds,
            importDirectoryIds = importDirectoryIds,
            apiCredentialIds = apiCredentialIds,
            apiSessionIds = apiSessionIds,
            apiRequestIds = apiRequestIds,
            apiResponseIds = apiResponseIds,
        )
    }

    // endregion
}

/**
 * Records what a person's edit of a transfer changed as their overrides, so a later rebuild from the observations keeps
 * it: each field whose stored value moved, each attribute set or changed, and each attribute removed. What the edit left
 * as it was records nothing, so an untouched field still follows what the sources say.
 */
private suspend fun PersonOverrideWriteRepository.recordTransferEdit(
    transferId: TransferId,
    before: MaterializedTransaction,
    after: MaterializedTransaction,
) {
    for (field in TransferField.entries) {
        val value = after.values.column(field) ?: continue
        if (value != before.values.column(field)) setTransferOverride(transferId, field, value)
    }
    val was = before.attributes.associate { (it.typeId to it.groupKey) to it.value }
    val now = after.attributes.associate { (it.typeId to it.groupKey) to it.value }
    for ((key, value) in now) {
        if (was[key] != value) setTransferAttributeOverride(transferId, AttributeOverride.Set(key.first, key.second, value))
    }
    for (key in was.keys - now.keys) {
        setTransferAttributeOverride(transferId, AttributeOverride.Removed(key.first, key.second))
    }
}

private fun AttributeTypeId.tradeOverride(value: String?): AttributeOverride =
    if (value == null) AttributeOverride.Removed(this, "") else AttributeOverride.Set(this, "", value)

private fun Source.isImport(): Boolean = this is Source.Csv || this is Source.Api
