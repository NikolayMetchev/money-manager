package com.moneymanager.importer

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.CryptoId
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.NewRelationship
import com.moneymanager.domain.model.Relationship
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.Trade
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.TransactionKind
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferAttribute
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.calendar.BusinessDayCalendar
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.observation.ObservedValues
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
import com.moneymanager.importengineapi.AccountMatchKey
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.AssetAliasMutation
import com.moneymanager.importengineapi.DedupePolicy
import com.moneymanager.importengineapi.EditGate
import com.moneymanager.importengineapi.ExpandedRow
import com.moneymanager.importengineapi.ExpandedTransfer
import com.moneymanager.importengineapi.ExpansionRow
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportOperation
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
import com.moneymanager.importengineapi.LocalTradeKey
import com.moneymanager.importengineapi.MaterializationReport
import com.moneymanager.importengineapi.MaterializationRequest
import com.moneymanager.importengineapi.ObservationExpander
import com.moneymanager.importengineapi.ObservationKey
import com.moneymanager.importengineapi.ObservationMutation
import com.moneymanager.importengineapi.ObservationSlot
import com.moneymanager.importengineapi.RelationshipMutation
import com.moneymanager.importengineapi.RowOutcome
import com.moneymanager.importengineapi.TradeDedupePolicy
import com.moneymanager.importengineapi.WriteIntent
import com.moneymanager.importengineapi.forRow
import com.moneymanager.importengineapi.observationContent
import com.moneymanager.importengineapi.observationKey
import com.moneymanager.importengineapi.observationOwner
import com.moneymanager.tasks.Progress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant

/** Upper bound on the legs walked along one pass-through chain; real chains are a few hops (card → Curve → PayPal → merchant). */
private const val MAX_PASS_THROUGH_CHAIN_LEGS = 16

internal val FEE_ACCOUNT_FOR_TYPE = AttributeTypeId(WellKnownIds.ACCOUNT_FEE_ACCOUNT_FOR_ATTR_TYPE_ID)

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
    /** The locations business-day reconcile windows count on. */
    private val holidayCalendarRepository: HolidayCalendarWriteRepository,
    /** Transfer relationships: the fee and `pass-through` links a duplicate row observes beside its twin. */
    private val relationshipRepository: RelationshipWriteRepository,
    /** Writes shadow→real reconciliation links. */
    private val reconciliationLinkRepository: ReconciliationLinkWriteRepository,
    /** Observations and the materialized rows built from them, with [personOverrideRepository] the overrides on top. */
    private val observationRepository: ObservationWriteRepository,
    private val personOverrideRepository: PersonOverrideWriteRepository,
    /** Staged files' import records. */
    private val importRecordRepository: ImportRecordWriteRepository,
    private val editGate: EditGate,
) : ImportEngine {
    private val commitLock = Mutex()
    private val writes = MutableStateFlow(0L)
    override val writeVersion: StateFlow<Long> = writes.asStateFlow()

    private val copier =
        ReconciliationCopier(observationRepository, reconciliationLinkRepository, accountAttributeRepository, ::materialize)
    private val rerun =
        RerunSettlement(
            observationRepository,
            personOverrideRepository,
            relationshipRepository,
            transactionRepository,
            tradeRepository,
            copier,
            ::placeholderTypeId,
            ::materialize,
        )
    private val handEdits = HandEdits(observationRepository, personOverrideRepository)
    private val accountResolver = AccountResolver(accountRepository, accountAttributeRepository)
    private val personResolver = PersonResolver(personRepository, personAttributeRepository, ownershipRepository)
    private val configMutations =
        ConfigMutationWriter(
            accountAttributeRepository,
            accountMappingRepository,
            apiImportStrategyRepository,
            apiSessionRepository,
            csvImportRepository,
            csvImportStrategyRepository,
            holidayCalendarRepository,
            importDirectoryRepository,
            importRecordRepository,
            observationRepository,
            passThroughAccountRepository,
            personOverrideRepository,
            reconciliationLinkRepository,
            settingsRepository,
            tradeRepository,
            handEdits,
        )

    override suspend fun import(
        batch: ImportBatch,
        onProgress: (suspend (Progress) -> Unit)?,
        batchSize: Int,
    ): ImportResult =
        commitLock.withLock {
            try {
                applyBatch(batch, onProgress, batchSize)
            } finally {
                writes.update { it + 1 }
            }
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
        val sharedByResets = rerun.transactionsObservedByResets(batch)
        val config = configMutations.applyConfigMutations(batch)

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
        val vacant = observationRepository.getVacantTransactionIds()
        val tradeReconciler = buildTradeReconciler(batch, vacant)
        // The same movement again, but recorded by the other source as *transfers*: a conversion whose
        // credits it could not attribute to its debits. There is no trade id to report for such a
        // match, so the order links resolved below would have nothing to point at - hence trades an
        // order claims are held back from it. A conversion is never part of an order.
        val orderLinkedTradeKeys = batch.orders.flatMapTo(mutableSetOf()) { it.tradeKeys }
        val conversionReconciler = buildConversionTradeReconciler(batch, orderLinkedTradeKeys, vacant)
        val observer = sourceObservations()
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
                    reuseId = observer.reuse(intent.observationKey(), TransactionKind.TRADE) as TradeId?,
                )
            tradeOccurrences[tupleKey] = occurrence + 1
        }
        val tradeResults =
            if (tradesToWrite.isEmpty()) emptyList() else tradeRepository.createTrades(tradesToWrite.map { it.second })
        for ((toWrite, tradeResult) in tradesToWrite.zip(tradeResults)) {
            val (intent, written) = toWrite
            // A re-imported trade loses the attributes its source owns but no longer reports (e.g. the
            // `excluded` of a trade the source has since un-deleted), like a transfer UPDATE; so does one a re-run takes back.
            if (!tradeResult.created || written.reuseId != null) {
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
        // A record observes the trade it lands on, whether it created it or found it again (an overlapping session's
        // fill): unimporting the record that created it must not take it while another still says it.
        observer.recordAll(
            batch.trades.mapNotNull { intent ->
                val tradeId = createdTradeIds[intent.key] ?: return@mapNotNull null
                SourceBooking(intent.observationKey(), intent.source, tradeId) { intent.observationContent() }
            },
        )
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
        val accountResolution = accountResolver.resolveAccounts(batch.copy(accountsToCreate = batch.accountsToCreate.creates()))
        batch.shadowSource?.let { source ->
            val shadowIds = accountResolver.shadowAccountIds(source)
            val strays = accountResolution.keyToId.values.filterNot { it in shadowIds }
            require(strays.isEmpty()) { "Reconciliation import for '$source' resolved onto non-shadow account(s) $strays" }
        }

        onProgress?.invoke(Progress("Resolving people"))
        val personResolution = personResolver.resolvePeople(batch.copy(peopleToCreate = batch.peopleToCreate.creates()))

        onProgress?.invoke(Progress("Linking ownerships"))
        personResolver.createOwnerships(
            batch.copy(ownerships = batch.ownerships.creates()),
            personResolution,
            accountResolution.keyToId,
        )

        val transfers = importTransferCreates(batch.transfers.creates(), batch, accountResolution.keyToId, batchSize, onProgress)

        // ----- UPDATE phase -----
        // A person's edits become their overrides as well, so a rebuild from the observations keeps them.
        val handEditSnapshots = handEdits.snapshot(batch.transfers.updates())
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
        handEdits.record(handEditSnapshots)
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
            val relationships = relationshipRepository
            relationships.apply(
                batch.relationshipMutations.map { m ->
                    when (m) {
                        is RelationshipMutation.Create -> {
                            RelationshipWrite.Create(
                                m.typeId,
                                m.members,
                                m.source,
                                pinObservations = m.pinObservations,
                            )
                        }

                        is RelationshipMutation.Delete -> {
                            RelationshipWrite.Delete(m.id)
                        }
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
            rerun.stillObserved(
                // A person deleting a transaction deletes it; only a source withdrawing its records leaves the others standing.
                transferDeletes.filter { it.source.isImport() }.map { requireNotNull(it.existingId) } +
                    tradeDeletes.filter { it.source.isImport() }.map { requireNotNull(it.existingId) },
                (transferDeletes.map { it.source } + tradeDeletes.map { it.source }).toSet(),
            )
        // A re-run's reserved transactions keep their rows (#1510): each stands vacant, hidden from the re-run's own dedupe
        // and matching, until its record says it again (and it is written only if what it says changed) or the settle
        // deletes it. Their links stay with them.
        val reserved = rerun.reserveVacated(batch, (transferDeleteIds + tradeDeleteIds).filterNot { it in stillObserved })
        // What a withdrawn record was reconciled with is rebuilt once it goes: a link left with one member is pruned, so
        // the exclusion it held lifts, as a fresh import of the others has it.
        val partners =
            rerun.reconciledPartnersOf(
                transferDeletes.filter { it.source.isImport() }.map { requireNotNull(it.existingId) } +
                    tradeDeletes.filter { it.source.isImport() }.map { requireNotNull(it.existingId) },
                stillObserved + reserved.ids,
            )
        transferDeleteIds.filterNot { it in stillObserved }.chunked(DELETE_CHUNK_ROWS).forEach { chunk ->
            val gone = chunk.filterNot { it in reserved.ids }
            observationRepository.deleteObservationsOfTransactions(gone)
            transactionRepository.deleteTransactions(gone)
            reportDeleted(chunk.size)
        }
        tradeDeleteIds.filterNot { it in stillObserved }.chunked(DELETE_CHUNK_ROWS).forEach { chunk ->
            val gone = chunk.filterNot { it in reserved.ids }
            observationRepository.deleteObservationsOfTransactions(gone)
            tradeRepository.deleteTrades(gone)
            reportDeleted(chunk.size)
        }
        // A transaction the reset left with no observation at all stays as its import booked it until the file's re-import:
        // rebuilding it from nothing would delete what the reset file's other rows still report.
        val rebuildCandidates = sharedByResets - (transferDeleteIds + tradeDeleteIds).toSet() + partners - reserved.ids
        val rebuilt =
            observationRepository
                .getObservationsOfTransactions(rebuildCandidates)
                .mapTo(stillObserved.toMutableSet()) { it.transactionId }
        if (rebuilt.isNotEmpty()) materialize(emptyList(), MaterializationRequest(Source.Reconciler, rebuilt))
        // An account an observation still names isn't empty, even when no transaction shows it: a joined twin's
        // other record keeps its own account (a placeholder) while the merge books the real one.
        val accountDeletes = batch.accountsToCreate.deletes().map { requireNotNull(it.existingId) }
        val observed = observationRepository.getAccountsWithObservations(accountDeletes)
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
            onProgress?.invoke(Progress("Refreshing matched transactions"))
            materialize(emptyList(), MaterializationRequest(Source.Reconciler, transfers.observedDuplicates))
        }
        val matchSync = batch.takeIf { it.syncMatches }?.let { matchSync().run(onProgress) } ?: MaterializationReport()
        // A transaction a re-run took back was written with what its own record says: bring it to the merge of every
        // observation and link it has, as a fresh booking would be, writing only what differs (#1510).
        val retaken = transfers.retaken + observer.claimed
        if (retaken.isNotEmpty()) materialize(emptyList(), MaterializationRequest(Source.Reconciler, retaken))
        val settled = batch.settle?.let { rerun.settle(it, onProgress) }
        val copied = if (batch.reconciliationCopies.isEmpty()) emptyList() else copier.copyToRealAccounts(batch.reconciliationCopies)

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
            materialization = settled ?: materialization,
            matchSync = matchSync,
            reserved = reserved,
            copiedTransactionIds = copied,
        )
    }

    private suspend fun materialize(
        mutations: List<ObservationMutation>,
        request: MaterializationRequest,
    ): MaterializationReport {
        val observations = observationRepository
        val overrides = personOverrideRepository
        val plan =
            ObservationMaterializationPlanner(
                observations = observations,
                overrides = overrides,
                relationships = relationshipRepository,
                placeholderTypeId = placeholderTypeId(),
                records = importRecordRepository,
            ).plan(mutations, request)
        return ObservationMaterialization(observations, accountRepository, tradeRepository).apply(plan)
    }

    private suspend fun placeholderTypeId(): AttributeTypeId? =
        attributeTypeRepository.getByName(WellKnownIds.UNIDENTIFIED_COUNTERPARTY_ATTR_TYPE_NAME).first()?.id

    private suspend fun matchSync(): MatchSync {
        val observations = observationRepository
        val importRecords = importRecordRepository
        val calendars = loadSettlementCalendars()
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
            overrides = personOverrideRepository,
            calendarFor = calendars::forAccounts,
            materialize = ::materialize,
        )
    }

    /**
     * Records what API items and hand entries say about the transactions this batch books, beside the rows themselves
     * (#1429, #1430): the observations staged files will be matched against once they sync (#1428).
     */
    private suspend fun sourceObservations(): SourceObservations {
        val observations = observationRepository
        val unidentified =
            attributeTypeRepository.getByName(WellKnownIds.UNIDENTIFIED_COUNTERPARTY_ATTR_TYPE_NAME).first()?.id
        return SourceObservations(observations, importRecordRepository, unidentified)
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
        /** The kept transactions a re-run took back (#1510). */
        val retaken: Set<TransactionId> = emptySet(),
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
        // A re-run's vacated rows stand for what it is re-booking, so it books them afresh rather than matching them (#1510).
        val vacant = observationRepository.getVacantTransactionIds()
        val existing = loadExisting(resolvedTransfers, batch, calendars).filterNot { it.transferId in vacant }
        val existingRelationships =
            existingRelationships(existing).filterNot { relationship -> relationship.members.any { it.transactionId in vacant } }
        val onward = passThroughOnward(existing, existingRelationships)
        val classified = ImportDeduper(batch.dedupePolicy, existing, ownSourceLegs(resolvedTransfers, existing)).classify(resolvedTransfers)

        val toImport = resolveReversalLinks(classified.filter { it.status == ImportStatus.IMPORTED })
        val toUpdate = classified.filter { it.status == ImportStatus.UPDATED }
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
        val observer = sourceObservations()
        // A re-run's row that still says its reserved transaction is recorded as its observation and brought to the merge of every
        // record on it below (#1510): writing what this row alone says first would rewrite a twin only to write it back.
        val directUpdates = toUpdate.filterNot { observer.isReserved(it.transfer.observationKey()) }
        val createdIds = writeTransfers(toImport, directUpdates, batchSize, onProgress, engineOwnedTypeId, createdLegs, observer)
        if (createdLegs.isNotEmpty()) onProgress?.invoke(Progress("Recording observations"))
        val approximate =
            toImport.mapNotNullTo(mutableSetOf()) { c -> c.transfer.takeIf { it.approximateUntil != null }?.observationKey() }
        observer.recordTransfers(createdLegs.map { (leg, id) -> TransferObservation(leg, id, leg.key in approximate) })
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
        val observedDuplicates = observeDuplicates(classified, orderedRowOutcomes, existingRelationships, onward, observer, onProgress)

        return TransferOutcome(
            imported = toImport.size,
            duplicates = duplicates,
            updated = toUpdate.size,
            excluded = excluded,
            createdTransferIds = createdTransferIds,
            rowOutcomes = rowOutcomes,
            orderedRowOutcomes = orderedRowOutcomes,
            observedDuplicates = observedDuplicates,
            retaken = observer.claimed,
        )
    }

    /**
     * A row that duplicates a transfer already booked (or one this batch books) says that transfer too: its main leg,
     * and its fee leg when the transfer has a fee, observe it beside what booked it, so twins are one transaction with
     * several observations. A row seen before (a re-import of its file) already says its slots and keeps them.
     * A row that updates a transfer says it only when its re-run reserved the slot (#1429): it takes its observation back
     * there, so what a person decided about it stays; a fresh import leaves the update to the transfer alone.
     */
    private suspend fun observeDuplicates(
        classified: List<Classified>,
        outcomes: List<RowOutcome>,
        existingRelationships: List<Relationship>,
        onward: Map<TransferId, List<Transfer>>,
        observer: SourceObservations,
        onProgress: (suspend (Progress) -> Unit)?,
    ): Set<TransactionId> {
        val duplicates =
            classified.zip(outcomes).mapNotNull { (c, outcome) ->
                outcome.transferId
                    ?.takeIf {
                        c.status == ImportStatus.DUPLICATE ||
                            (c.status == ImportStatus.UPDATED && observer.isReserved(c.transfer.observationKey()))
                    }?.let { c to it }
            }
        if (duplicates.isEmpty()) return emptySet()
        onProgress?.invoke(Progress("Recording duplicate rows"))
        val feeLegs =
            existingRelationships
                .mapNotNull { relationship ->
                    relationship.transferPair?.let { (primary, related) -> (primary to relationship.relationshipType.id) to related }
                }.toMap()
        val rows = ObservationExpander.expandTransfers(duplicates.map { (c, _) -> ExpansionRow(c.transfer) }, emptyMap())
        val legs =
            buildList {
                for ((duplicate, row) in duplicates.zip(rows)) {
                    val (c, id) = duplicate
                    val approximate = c.transfer.approximateUntil != null
                    add(TransferObservation(row.main, id, approximate))
                    val feeLeg = row.legs.firstOrNull { it.key.slot == ObservationSlot.FEE }
                    val feeId = c.transfer.fee?.let { feeLegs[id to it.relationshipTypeId] }
                    if (feeLeg != null && feeId != null) add(TransferObservation(feeLeg, feeId, approximate))
                    // The onward movement outlives whichever of the two records is unimported first.
                    row.legs
                        .filter { it.key.slot.startsWith(ObservationSlot.CONDUIT_PREFIX) }
                        .zip(onward[id].orEmpty())
                        .takeWhile { (leg, booked) ->
                            leg.sourceAccountId == booked.sourceAccountId && leg.targetAccountId == booked.targetAccountId
                        }.forEach { (leg, booked) ->
                            add(TransferObservation(leg, booked.id, approximate))
                        }
                }
            }
        return observer.recordTransfers(legs)
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
        observer: SourceObservations,
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
                    reuseId = observer.reuse(trade.key, TransactionKind.TRADE) as TradeId?,
                )
            }
        if (trades.isEmpty()) return
        val results = tradeRepository.createTrades(trades)
        for ((index, result) in results.withIndex()) {
            val trade = expanded[index]
            if (result.created) {
                observer.record(trade.key, trade.provenance, result.id) { trade.observationContent() }
            } else {
                observer.reclaim(trade.key, result.id) { trade.observationContent() }
            }
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
        val repository = relationshipRepository
        if (existing.isEmpty()) return emptyList()
        return repository.getByTransactions(existing.map { it.transferId })
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
    private suspend fun buildTradeReconciler(
        batch: ImportBatch,
        vacant: Set<TransactionId>,
    ): TradeReconciler? {
        val policy = batch.tradeDedupePolicy as? TradeDedupePolicy.Fuzzy ?: return null
        val creates = batch.trades.creates().filter { it.timestamp != null }
        if (creates.isEmpty()) return null
        val accountIds = creates.flatMap { listOfNotNull(it.fromAccountId, it.toAccountId) }.toSet()
        if (accountIds.isEmpty()) return null
        val minTs = creates.minOf { requireNotNull(it.timestamp) } - policy.window
        val maxTs = creates.maxOf { requireNotNull(it.timestamp) } + policy.window
        val existing = tradeRepository.getTradesByAccountsAndDateRange(accountIds, minTs, maxTs).filterNot { it.id in vacant }
        return if (existing.isEmpty()) null else TradeReconciler(policy, existing)
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
        vacant: Set<TransactionId>,
    ): ConversionTradeReconciler? {
        val policy = batch.tradeDedupePolicy as? TradeDedupePolicy.Fuzzy ?: return null
        val typeName = policy.conversionRelationshipTypeName ?: return null
        val relationships = relationshipRepository
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
                .filterNot { it.id in vacant }
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
                // A vacant credit leg (#1510) is no conversion to suppress a trade with: no record would take it back, and settling
                // would delete it, so the trade it records is booked instead.
                val credit = creditLegs[creditId]?.takeUnless { it.id in vacant } ?: return@mapNotNull null
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
    private suspend fun settlementCalendars(policy: DedupePolicy): SettlementCalendars =
        if (policy.windows().none { it is SettlementWindow.BusinessDays }) SettlementCalendars.WEEKENDS_ONLY else loadSettlementCalendars()

    private suspend fun loadSettlementCalendars(): SettlementCalendars {
        val locations =
            accountAttributeRepository
                .getByType(AttributeTypeId(WellKnownIds.ACCOUNT_LOCATION_ATTR_TYPE_ID))
                .first()
                .associate { it.accountId to it.value }
        return SettlementCalendars(locations, settingsRepository.getDefaultLocation().first(), holidayCalendarRepository.getAll().first())
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
        val candidates = sourceTransferSnapshots((rawTransfers + approximateLegs).distinctBy { it.id }, transfers, batch.strategyId)
        return candidates.map { transfer ->
            ExistingTransferInfo(
                transferId = transfer.id,
                transfer = transfer,
                attributes = transfer.attributes.associate { it.attributeType.id to it.value },
                uniqueKey = uniqueKeyExtractor?.extract(transfer).orEmpty(),
                apiId = apiIdExtractor?.extract(transfer),
            )
        }
    }

    /** Recognize an unchanged source row even when another observation contributed attributes to its transaction. */
    private suspend fun sourceTransferSnapshots(
        candidates: List<Transfer>,
        incoming: List<ImportTransfer>,
        strategyId: String?,
    ): List<Transfer> {
        if (candidates.isEmpty() || incoming.isEmpty()) return candidates
        val observations = observationRepository
        val records = importRecordRepository
        val loader =
            MatchScopeLoader(
                observations,
                records,
                csvImportRepository,
                apiSessionRepository,
                accountAttributeRepository,
                attributeTypeRepository,
            )
        val families =
            incoming
                .mapNotNull {
                    when {
                        strategyId == null -> loader.familyOf(it.source)
                        it.source is Source.Csv -> MatchScopeLoader.csvFamily(strategyId)
                        it.source is Source.Api -> MatchScopeLoader.apiFamily(strategyId)
                        else -> null
                    }
                }.toSet()
        if (families.isEmpty()) return candidates
        val stored = observations.getObservationsOfTransactions(candidates.map { it.id })
        if (stored.isEmpty()) return candidates
        val reservedIds = observations.getReservedIds(stored.map { it.id })
        val reserved = stored.filter { it.id in reservedIds }.mapTo(mutableSetOf()) { it.transactionId }
        val byId = candidates.associateBy { it.id }
        val types = attributeTypeRepository.getAll().first().associateBy { it.id }
        val snapshots =
            loader.toMatch(stored, emptyMap()).mapNotNull { observation ->
                // A re-run must reclaim its reserved slot through the normal update/create path.
                if (observation.current in reserved) return@mapNotNull null
                if (observation.family !in families || observation.ref.slot != ObservationSlot.MAIN) return@mapNotNull null
                val values =
                    observation.content.values as? ObservedValues.Transfer
                        ?: return@mapNotNull null
                val transfer = byId[observation.current] ?: return@mapNotNull null
                if (transfer.amount.asset.id.id != values.assetId.id) return@mapNotNull null
                transfer.copy(
                    timestamp = values.timestamp,
                    description = values.description,
                    sourceAccountId = values.sourceAccountId,
                    targetAccountId = values.targetAccountId,
                    amount = transfer.amount.copy(amount = values.amount),
                    attributes =
                        observation.content.attributes.map { attribute ->
                            TransferAttribute(
                                0,
                                transfer.id,
                                types.getValue(attribute.typeId),
                                attribute.value,
                                attribute.groupKey,
                            )
                        },
                )
            }

        data class SnapshotKey(
            val timestamp: Instant?,
            val description: String,
            val source: AccountId,
            val target: AccountId,
            val amount: Money?,
            val attributes: Map<AttributeTypeId, String>,
        )
        val incomingKeys =
            incoming.mapNotNullTo(mutableSetOf()) { row ->
                val from = (row.fromAccount as? AccountRef.Existing)?.id ?: return@mapNotNullTo null
                val to = (row.toAccount as? AccountRef.Existing)?.id ?: return@mapNotNullTo null
                SnapshotKey(
                    row.timestamp,
                    row.description,
                    from,
                    to,
                    row.amount,
                    row.attributes.associate { it.typeId to it.value },
                )
            }
        val identical =
            snapshots
                .filter { snapshot ->
                    SnapshotKey(
                        snapshot.timestamp,
                        snapshot.description,
                        snapshot.sourceAccountId,
                        snapshot.targetAccountId,
                        snapshot.amount,
                        snapshot.attributes.associate { it.attributeType.id to it.value },
                    ) in incomingKeys
                }.associateBy { it.id }
        return candidates.map { identical[it.id] ?: it }
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
        observer: SourceObservations,
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
            // A leg whose record a re-run reserved an id for goes back in under it (#1430).
            val reusedIds = mutableMapOf<TransferId, TransferId>()
            val joinedIds = mutableMapOf<TransferId, TransferId>()
            chunk.flatMap { it.legs }.forEachIndexed { i, leg ->
                val placeholder = payload.transfers[i].id
                val reused = observer.reuse(leg.key, TransactionKind.TRANSFER) as TransferId?
                if (reused != null) {
                    reusedIds[placeholder] = reused
                } else {
                    (observer.joined(leg.key, TransactionKind.TRANSFER) as TransferId?)?.let { joinedIds[placeholder] = it }
                }
            }
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
                    reusedIds = reusedIds,
                    joinedIds = joinedIds,
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
     * un-deleted must again.
     */
    private suspend fun derivedLegExclusionUpdates(
        exclusionChanges: Map<TransferId, ExclusionChange>,
        excludedTypeId: AttributeTypeId,
        alreadyUpdated: Set<TransferId>,
    ): List<Pair<TransferUpdate, Source>> {
        val relationships = relationshipRepository
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

    private fun requireId(ref: AccountRef?): AccountId =
        when (ref) {
            is AccountRef.Existing -> ref.id
            is AccountRef.Local -> error("Unresolved account reference: ${ref.key}")
            null -> error("Missing account reference")
        }

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
        // A copy is the one write a source's row makes on the real accounts, and only a batch of its own makes it.
        require(batch.reconciliationCopies.isEmpty()) { "Reconciliation import for '$source' may not copy onto real accounts" }
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
        val strays = referenced - accountResolver.shadowAccountIds(source)
        require(strays.isEmpty()) { "Reconciliation import for '$source' references non-shadow account(s) $strays" }
    }
}

private fun Source.isImport(): Boolean = this is Source.Csv || this is Source.Api
