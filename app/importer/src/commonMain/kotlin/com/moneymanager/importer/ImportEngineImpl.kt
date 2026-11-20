package com.moneymanager.importer

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.CryptoId
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Relationship
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.Trade
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.TransactionKind
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.csv.ImportStatus
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
import com.moneymanager.importengineapi.AccountMatchKey
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.AssetAliasMutation
import com.moneymanager.importengineapi.DedupePolicy
import com.moneymanager.importengineapi.EditGate
import com.moneymanager.importengineapi.ExpandedTransfer
import com.moneymanager.importengineapi.ExpansionRow
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportOperation
import com.moneymanager.importengineapi.ImportResult
import com.moneymanager.importengineapi.ImportRowKey
import com.moneymanager.importengineapi.ImportTradeIntent
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.LocalAccountKey
import com.moneymanager.importengineapi.LocalCategoryKey
import com.moneymanager.importengineapi.LocalCryptoKey
import com.moneymanager.importengineapi.LocalCurrencyKey
import com.moneymanager.importengineapi.LocalTradeKey
import com.moneymanager.importengineapi.MaterializationReport
import com.moneymanager.importengineapi.MaterializationRequest
import com.moneymanager.importengineapi.ObservationExpander
import com.moneymanager.importengineapi.ObservationMutation
import com.moneymanager.importengineapi.ObservationSlot
import com.moneymanager.importengineapi.RelationshipMutation
import com.moneymanager.importengineapi.RowOutcome
import com.moneymanager.importengineapi.TradeDedupePolicy
import com.moneymanager.importengineapi.WriteIntent
import com.moneymanager.importengineapi.observationContent
import com.moneymanager.importengineapi.observationKey
import com.moneymanager.tasks.Progress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant

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
    private val transferWriter = TransferWriter(transactionRepository, relationshipTypeRepository, relationshipRepository)
    private val existingTransfers =
        ExistingTransferLoader(
            transactionRepository,
            accountAttributeRepository,
            attributeTypeRepository,
            csvImportRepository,
            apiSessionRepository,
            settingsRepository,
            holidayCalendarRepository,
            relationshipRepository,
            observationRepository,
            importRecordRepository,
        )
    private val reversalLinker = ReversalLinker(transactionRepository, relationshipTypeRepository)
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
        val calendars = existingTransfers.loadSettlementCalendars()
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
        val calendars = existingTransfers.settlementCalendars(batch.dedupePolicy)
        // A re-run's vacated rows stand for what it is re-booking, so it books them afresh rather than matching them (#1510).
        val vacant = observationRepository.getVacantTransactionIds()
        val existing = existingTransfers.loadExisting(resolvedTransfers, batch, calendars).filterNot { it.transferId in vacant }
        val existingRelationships =
            existingTransfers.existingRelationships(existing).filterNot { relationship ->
                relationship.members.any {
                    it.transactionId in
                        vacant
                }
            }
        val onward = existingTransfers.passThroughOnward(existing, existingRelationships)
        val classified =
            ImportDeduper(
                batch.dedupePolicy,
                existing,
                existingTransfers.ownSourceLegs(resolvedTransfers, existing),
            ).classify(resolvedTransfers)

        val toImport = reversalLinker.resolveReversalLinks(classified.filter { it.status == ImportStatus.IMPORTED })
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
        val createdIds =
            transferWriter.writeTransfers(
                toImport,
                directUpdates,
                batchSize,
                onProgress,
                engineOwnedTypeId,
                createdLegs,
                observer,
            )
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
