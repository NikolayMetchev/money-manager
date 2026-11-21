package com.moneymanager.importer

import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.WellKnownIds
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
import com.moneymanager.domain.repository.write.ReportedBalanceWriteRepository
import com.moneymanager.domain.repository.write.SettingsWriteRepository
import com.moneymanager.domain.repository.write.TradeWriteRepository
import com.moneymanager.domain.repository.write.TransactionWriteRepository
import com.moneymanager.importengineapi.EditGate
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportResult
import com.moneymanager.importengineapi.MaterializationReport
import com.moneymanager.importengineapi.MaterializationRequest
import com.moneymanager.importengineapi.ObservationMutation
import com.moneymanager.importengineapi.RelationshipMutation
import com.moneymanager.tasks.Progress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    cryptoRepository: CryptoWriteRepository,
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
    /** The running balances statements report beside their records. */
    private val reportedBalanceRepository: ReportedBalanceWriteRepository,
    private val editGate: EditGate,
) : ImportEngine {
    private val commitLock = Mutex()
    private val writes = MutableStateFlow(0L)
    override val writeVersion: StateFlow<Long> = writes.asStateFlow()

    private val balanceWriter = ReportedBalanceWriter(reportedBalanceRepository)
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
    private val tradeImporter =
        TradeImporter(
            tradeRepository,
            exchangeOrderRepository,
            transactionRepository,
            relationshipRepository,
            relationshipTypeRepository,
            observationRepository,
        )
    private val transferImporter =
        TransferImporter(
            transactionRepository,
            observationRepository,
            existingTransfers,
            reversalLinker,
            transferWriter,
            tradeImporter,
            handEdits,
            ::sourceObservations,
        )
    private val referenceData =
        ReferenceDataWriter(
            currencyRepository,
            cryptoRepository,
            categoryRepository,
            accountRepository,
            personRepository,
            ownershipRepository,
            observationRepository,
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
        batch.shadowSource?.let { requireShadowOnlyReferences(batch, it, accountResolver) }

        // ----- Lookup-table resolution (first: ids feed attributes/relationships built by callers) -----
        val attributeTypeIds = batch.attributeTypeNames.associateWith { attributeTypeRepository.getOrCreate(it) }
        val relationshipTypeIds = batch.relationshipTypeNames.associateWith { relationshipTypeRepository.getOrCreate(it) }
        // Read once, after the batch's own attribute types exist: every observation step of the batch merges with it.
        placeholderType = attributeTypeRepository.getByName(WellKnownIds.UNIDENTIFIED_COUNTERPARTY_ATTR_TYPE_NAME).first()?.id

        // ----- Config / staging / session / settings / device mutations -----
        // Read before a reset drops its file's observations: the transactions they shared with other records.
        val sharedByResets = rerun.transactionsObservedByResets(batch)
        val config = configMutations.applyConfigMutations(batch)

        // ----- CREATE phase (first, so batch-local keys resolve for dependents) -----
        val assets = referenceData.createAssets(batch)
        val observer = sourceObservations()
        val trades = tradeImporter.importTrades(batch, observer)
        val createdCategoryIds = referenceData.createCategories(batch)

        onProgress?.invoke(Progress("Resolving accounts"))
        val accountResolution = accountResolver.resolveAccounts(batch.copy(accountsToCreate = batch.accountsToCreate.creates()))
        batch.shadowSource?.let { requireShadowOnlyResolution(it, accountResolution.keyToId.values, accountResolver) }

        onProgress?.invoke(Progress("Resolving people"))
        val personResolution = personResolver.resolvePeople(batch.copy(peopleToCreate = batch.peopleToCreate.creates()))

        onProgress?.invoke(Progress("Linking ownerships"))
        personResolver.createOwnerships(
            batch.copy(ownerships = batch.ownerships.creates()),
            personResolution,
            accountResolution.keyToId,
        )

        val transfers =
            transferImporter.importTransferCreates(
                batch.transfers.creates(),
                batch,
                accountResolution.keyToId,
                batchSize,
                onProgress,
            )

        // ----- UPDATE phase -----
        transferImporter.applyUpdates(batch)
        tradeImporter.applyUpdates(batch)
        referenceData.applyUpdates(batch)

        // ----- RELATIONSHIPS between existing transactions (after the writes, so a batch can relate what it made) -----
        applyRelationshipMutations(batch)

        // ----- MERGE / UNMERGE (before deletes: a merge already removes the absorbed account) -----
        referenceData.applyMerges(batch)

        // ----- DELETE phase (dependents first) -----
        referenceData.deleteOwnerships(batch)
        val reserved = rerun.deleteTransactions(batch, sharedByResets, onProgress)
        referenceData.applyDeletes(batch)

        // ----- OBSERVATIONS (last: they name the accounts and assets the batch created) -----
        val materialization = batch.materialization?.let { materialize(batch.observationMutations, it) } ?: MaterializationReport()
        // A duplicate row joins its observation to a transaction another source materialized: bring that transaction to
        // the merge of both, so it says what both records say.
        if (transfers.observedDuplicates.isNotEmpty()) {
            onProgress?.invoke(Progress("Refreshing matched transactions"))
            materialize(emptyList(), MaterializationRequest(Source.Reconciler, transfers.observedDuplicates))
        }
        val matchSync = batch.takeIf { it.syncMatches }?.let { matchSync().run(onProgress) } ?: MaterializationReport()
        balanceWriter.write(batch, observer, accountResolution.keyToId)
        // A transaction a re-run took back was written with what its own record says: bring it to the merge of every
        // observation and link it has, as a fresh booking would be, writing only what differs (#1510).
        val retaken = transfers.retaken + observer.claimed
        if (retaken.isNotEmpty()) materialize(emptyList(), MaterializationRequest(Source.Reconciler, retaken))
        val settled = batch.settle?.let { rerun.settle(it, onProgress).also { balanceWriter.settle() } }
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
            createdCurrencyIds = assets.currencyIds,
            createdCryptoIds = assets.cryptoIds,
            createdTradeIds = trades.createdTradeIds,
            dedupedTradeKeys = trades.dedupedTradeKeys,
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

    private suspend fun applyRelationshipMutations(batch: ImportBatch) {
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

    /** The `unidentified-counterparty` attribute type of the batch being applied, which marks a record's placeholder end. */
    private var placeholderType: AttributeTypeId? = null

    private fun placeholderTypeId(): AttributeTypeId? = placeholderType

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
    private fun sourceObservations(): SourceObservations =
        SourceObservations(observationRepository, importRecordRepository, placeholderType)
}
