package com.moneymanager.csvimporter

import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.ImportFileKind
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.HardCodedCurrencyMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.importFileKind
import com.moneymanager.domain.repository.AccountAttributeReadRepository
import com.moneymanager.domain.repository.AccountMappingReadRepository
import com.moneymanager.domain.repository.CryptoReadRepository
import com.moneymanager.domain.repository.CsvImportStrategyReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.domain.repository.ImportRecordReadRepository
import com.moneymanager.domain.repository.PassThroughAccountReadRepository
import com.moneymanager.domain.repository.assetAliasMap
import com.moneymanager.importengineapi.CsvImportMutation
import com.moneymanager.importengineapi.CsvTransferSync
import com.moneymanager.importengineapi.CsvTransferSyncRow
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.PreparedImport
import com.moneymanager.importengineapi.RerunBlockedException
import com.moneymanager.importengineapi.RerunFile
import com.moneymanager.importengineapi.RerunReads
import com.moneymanager.importengineapi.executeRerun
import com.moneymanager.tasks.PhasedProgress
import com.moneymanager.tasks.Progress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/**
 * The source account and currency a file was last imported with, as its latest application recorded
 * them, so a re-import doesn't ask again. Each is null when the strategy read it per row, or when the
 * file was never imported.
 */
data class ImportedSettings(
    val accountId: AccountId?,
    val currencyId: CurrencyId?,
)

/** What this file's latest application ran with; see [ImportedSettings]. */
val CsvImport.importedSettings: ImportedSettings get() = ImportedSettings(lastAppliedAccountId, lastAppliedCurrencyId)

/** A strategy reads its currency from the record only when it maps one; otherwise the import supplies it. */
fun CsvImportStrategy.needsCurrencyOverride(): Boolean = config.fieldMappings[TransferField.CURRENCY] == null

/** This strategy with its currency hard-coded to [currencyId] (unchanged when null), for a file whose rows name none. */
fun CsvImportStrategy.withCurrency(currencyId: CurrencyId?): CsvImportStrategy {
    if (currencyId == null) return this
    val currencyMapping = HardCodedCurrencyMapping(fieldType = TransferField.CURRENCY, currencyId = currencyId)
    return copy(config = config.copy(fieldMappings = config.fieldMappings + (TransferField.CURRENCY to currencyMapping)))
}

/** The source a staged file's records are recorded under. */
val CsvImport.source: Source get() = Source.Csv(id)

/** What a "re-import all" will do: [files] are the files picked, in the order they were imported. */
data class ReimportAllPlan(
    val files: List<CsvImport>,
    val strategies: Map<Source, CsvImportStrategy?>,
    val imported: Map<Source, ImportedSettings>,
    /** What the run deletes and imports again: every file of [files] that can be. */
    val rerunFiles: List<RerunFile>,
) {
    val skippedNoStrategy: Int get() = strategies.count { it.value == null }

    /** Whether some file needs the shared account: its strategy has none and it created nothing to read one from. */
    val needsAccount: Boolean
        get() = files.any { strategies[it.source]?.needsSourceAccountOverride() == true && imported[it.source]?.accountId == null }

    val needsCurrency: Boolean
        get() = files.any { strategies[it.source]?.needsCurrencyOverride() == true && imported[it.source]?.currencyId == null }
}

/**
 * The one way to re-import staged files (CSV, QIF, OFX, Excel or PDF), one or many: [planAll] reads what they created and
 * [reimportAll] deletes it and runs their records through the normal import again, each with the strategy, account and
 * currency it was imported with. Other files are never re-run with them: what another file matched or reconciled
 * against is matched again by the engine (`MatchSync`), and the records keep their ids.
 * Each re-import holds [writeLock], the session's write lock, throughout: its delete, import, re-runs
 * and settle are separate writes, and an import between them would dedupe against a half-done state.
 */
@Suppress("LongParameterList", "TooManyFunctions")
class Reimports(
    val reads: RerunReads,
    private val csvImportStrategyRepository: CsvImportStrategyReadRepository,
    private val accountMappingRepository: AccountMappingReadRepository,
    private val accountAttributeRepository: AccountAttributeReadRepository,
    private val currencyRepository: CurrencyReadRepository,
    private val cryptoRepository: CryptoReadRepository,
    private val passThroughAccountRepository: PassThroughAccountReadRepository,
    private val maintenance: Maintenance,
    private val importEngine: ImportEngine,
    /** The import records a file's last import left, so a run can skip picked files nothing changed for; null always runs them. */
    private val importRecords: ImportRecordReadRepository? = null,
    private val writeLock: Mutex = Mutex(),
) {
    /** Runs [block] holding the session's write lock, for the re-imports that run outside this class (API sessions). */
    suspend fun <T> exclusively(block: suspend () -> T): T = writeLock.withLock { block() }

    /**
     * Plans a "re-import all" over [files], to run in the order they were imported. What each file created is read before
     * any of it is deleted. [onProgress] counts files read.
     */
    suspend fun planAll(
        files: List<CsvImport>,
        onProgress: (suspend (Progress) -> Unit)? = null,
    ): ReimportAllPlan {
        val progress = PhasedProgress(onProgress)
        val strategies = csvImportStrategyRepository.getAllStrategies().first()
        // A file with nothing to run over is left alone.
        val runnable = files.filter { it.rowCount > 0 && strategyFor(it.reloaded(), strategies) != null }.mapTo(mutableSetOf()) { it.id }
        // Each file is read once, all of them at once (reads don't block one another), off the caller's
        // dispatcher (a dialog's is the UI thread).
        val progressLock = Mutex()
        var filesRead = 0
        // Files imported at the same instant keep the order they were given.
        val ids = files.map { it.id }
        val count = ids.size
        val fileReads =
            withContext(Dispatchers.Default) {
                ids
                    .map { id ->
                        async {
                            val file = reads.csvImportRepository.getImport(id).first()
                            val read =
                                file?.takeIf { id in runnable }?.let {
                                    RerunFile(
                                        file = it.source,
                                        createdTransfers = reads.transferSourceRepository.getTransfersCreatedBy(it.source),
                                        tradeIds = reads.csvImportRepository.getTradeIdsCreatedByImport(id),
                                        createdAccountIds = reads.csvImportRepository.getAccountsCreatedByImport(id),
                                    )
                                }
                            // Files finish in any order, so the bar counts files read rather than following the list.
                            progressLock.withLock {
                                filesRead++
                                progress.emit(
                                    base = filesRead.toFloat() / count,
                                    detail = "File $filesRead of $count: ${file?.originalFileName.orEmpty()}",
                                )
                            }
                            file?.let { it to read }
                        }
                    }.awaitAll()
                    .filterNotNull()
                    .sortedWith(compareBy { it.first.importTimestamp })
            }
        val loaded = fileReads.map { it.first }
        val rerunFiles = fileReads.mapNotNull { it.second }
        return ReimportAllPlan(
            files = loaded,
            strategies = loaded.associate { it.source to strategyFor(it, strategies) },
            imported = loaded.associate { it.source to it.importedSettings },
            rerunFiles = rerunFiles,
        )
    }

    /** Previews a single CSV whose rows can all keep their existing, independent bookings. */
    suspend fun preview(
        selection: ReimportAllPlan,
        accountId: AccountId?,
        currencyId: CurrencyId?,
    ): PreparedImport? =
        writeLock.withLock {
            val picked = selection.files.singleOrNull() ?: return@withLock null
            if (importFileKind(picked.originalFileName) != ImportFileKind.CSV) return@withLock null
            val version = importEngine.planVersion() ?: return@withLock null
            val file = reads.csvImportRepository.getImport(picked.id).first() ?: return@withLock null
            if (file.lastAppliedAt == null || file.rowCount == 0) return@withLock null
            val strategy = strategyFor(file, csvImportStrategyRepository.getAllStrategies().first()) ?: return@withLock null
            val config = strategy.config
            if (strategy.worksheetName != null || config.pdfLines != null || config.reconciliation != null ||
                config.legGroups.isNotEmpty() || config.crossSourceReconcileWindow != null ||
                config.placeholderReconcileWindow != null || config.duplicateDateTolerance != null ||
                config.fundingAttributeMatch != null
            ) {
                return@withLock null
            }

            val saved = file.importedSettings
            val effective = strategy.withCurrency((saved.currencyId ?: currencyId).takeIf { strategy.needsCurrencyOverride() })
            val source = effectiveSourceFor(effective, saved.accountId ?: accountId)
            val mappings = accountMappingRepository.getAllMappings().first()
            val aliases = cryptoRepository.assetAliasMap()
            val passThroughs = passThroughAccountRepository.getAll().first()
            val matchers = AttributeAccountMatcher.registry(accountAttributeRepository.getAll().first())
            val rows = reads.csvImportRepository.getImportRows(file.id, limit = file.rowCount, offset = 0)
            if (rows.any { it.importStatus != ImportStatus.IMPORTED }) return@withLock null
            val accounts = reads.accountRepository
            val mapped =
                buildCsvMapper(
                    strategy = effective,
                    columns = file.columns,
                    accounts = accounts.accountsVisibleTo(effective),
                    currencies = currencies(),
                    accountMappings = mappings,
                    sourceAccountOverride = source,
                    passThroughAccounts = passThroughs,
                    historicalAccountNames = accounts.getPreviousAccountNames(),
                    cryptoAssets = cryptoRepository.getAllCryptoAssets().first(),
                    attributeAccountMatchers = matchers,
                    assetAliases = aliases,
                    fileName = file.originalFileName,
                    accountOwnerNames = accounts.getAccountOwnerNames(),
                ).prepareImport(rows)
            if (mapped.newAccounts.isNotEmpty() || mapped.validTransfers.size != rows.size ||
                mapped.validTransfers.any { !it.isIndependentBooking() }
            ) {
                return@withLock null
            }
            val inputsHash = stagedInputsHash(effective, source, mappings, aliases, matchers, passThroughs)
            importEngine.plan(
                CsvTransferSync(
                    id = file.id,
                    fileChecksum = file.fileChecksum,
                    rows = mapped.validTransfers.associate { it.rowIndex to CsvTransferSyncRow(it.transfer, it.statementAccountId) },
                    application =
                        CsvImportMutation.RecordApplication(
                            file.id,
                            effective.id,
                            effective.name,
                            Clock.System.now(),
                            source,
                            (effective.config.fieldMappings[TransferField.CURRENCY] as? HardCodedCurrencyMapping)?.currencyId,
                        ),
                    records = recordRowsMutation(file, rows, inputsHash),
                    version = version,
                ),
            )
        }

    suspend fun applyPreview(plan: PreparedImport): BulkReimportResult =
        writeLock.withLock {
            val applied = importEngine.apply(plan)
            if (applied) maintenance.refreshMaterializedViews()
            BulkReimportResult(
                filesImported = if (applied) 1 else 0,
                filesUnchanged = if (applied) 0 else 1,
                transfersCreated = 0,
                duplicatesSkipped = 0,
                filesSkippedNoStrategy = 0,
                filesFailed = 0,
                plan = plan.preview,
            )
        }

    /**
     * Re-imports every file of [plan]: one batch deletes what they all created and resets their rows, then each
     * file is imported again in the order it was first imported, as a fresh import of its rows, each row taking its
     * old ids back, and finally the ids no row took back are released, the empty accounts deleted and the materialized views refreshed, once each.
     * [accountId] and [currencyId] are for the files whose last import didn't record them. Progress names each
     * file by [fileLabel], and once [stopRequested] says yes no further file starts (see [runBulkReimport]): what
     * those files created is already deleted, so their rows wait, reset, for their next import.
     */
    suspend fun reimportAll(
        plan: ReimportAllPlan,
        accountId: AccountId?,
        currencyId: CurrencyId?,
        onProgress: suspend (Progress) -> Unit,
        fileLabel: (CsvImport) -> String = { it.originalFileName },
        stopRequested: () -> Boolean = { false },
    ): BulkReimportResult = writeLock.withLock { reimportAllLocked(plan, accountId, currencyId, onProgress, fileLabel, stopRequested) }

    /**
     * Plans [files]' re-import ([planAll]) and refuses it now if any file can't be imported again ([checkReimportable]),
     * so a caller's own write can sit between the two and is never made for a re-import that would be refused. Returns
     * the run: [reimportAll] of the plan, each file keeping the account and currency it was imported with.
     */
    suspend fun prepareAll(
        files: List<CsvImport>,
        onProgress: suspend (Progress) -> Unit,
        fileLabel: (CsvImport) -> String = { it.originalFileName },
    ): suspend () -> BulkReimportResult {
        val plan = planAll(files, onProgress)
        checkReimportable(plan, accountId = null, currencyId = null)
        return { reimportAll(plan, accountId = null, currencyId = null, onProgress = onProgress, fileLabel = fileLabel) }
    }

    /**
     * Throws [RerunBlockedException] when some file of [plan] would be deleted but can't be imported again. [reimportAll]
     * checks this itself; a caller with its own write to make first checks beforehand, so a refusal leaves that undone too.
     */
    fun checkReimportable(
        plan: ReimportAllPlan,
        accountId: AccountId?,
        currencyId: CurrencyId?,
    ) = refuseUnrunnable(
        plan.runFiles().map { file ->
            val imported = plan.imported.getValue(file.source)
            Triple(file, plan.strategies[file.source], ImportedSettings(imported.accountId ?: accountId, imported.currencyId ?: currencyId))
        },
    )

    private fun ReimportAllPlan.runFiles() = files.filter { file -> rerunFiles.any { it.file == file.source } }

    @Suppress("LongParameterList")
    private suspend fun reimportAllLocked(
        plan: ReimportAllPlan,
        accountId: AccountId?,
        currencyId: CurrencyId?,
        onProgress: suspend (Progress) -> Unit,
        fileLabel: (CsvImport) -> String,
        stopRequested: () -> Boolean,
    ): BulkReimportResult {
        val run = plan.runFiles()
        checkReimportable(plan, accountId, currencyId)
        val skipped = plan.files.count { it.rowCount > 0 && plan.strategies[it.source] == null && it !in run }
        if (run.isNotEmpty() && run.all { isUnchanged(it, plan, accountId, currencyId) }) {
            // Nothing any file reads changed, so importing them again would book what they booked: the run writes nothing.
            return BulkReimportResult(
                filesImported = 0,
                transfersCreated = 0,
                duplicatesSkipped = 0,
                filesSkippedNoStrategy = skipped,
                filesFailed = 0,
                filesUnchanged = run.size,
            )
        }
        // Three phases, each filling the bar from empty: the one delete batch, then runBulkReimport's two (preparing
        // the files, re-importing them). The steps after the last file (settling, empty accounts, views) keep the
        // files' bar where the run left it.
        var lastFileProgress: Progress? = null
        val onFileProgress: suspend (Progress) -> Unit = { inner ->
            val shifted = inner.copy(phase = inner.phase?.let { it.copy(number = it.number + 1, count = it.count + 1) })
            lastFileProgress = shifted
            onProgress(shifted)
        }
        val outcome =
            importEngine.executeRerun(
                files = plan.rerunFiles,
                resetRecords = ImportBatch(),
                reads = reads,
                maintenance = maintenance,
                onProgress = { step ->
                    val last = lastFileProgress
                    when {
                        last != null -> onProgress(last.copy(detail = step.detail, item = last.item?.copy(step = step.detail)))

                        // The delete batch; its closing "Re-importing" (no fraction) is the next phase's to say.
                        step.fraction != null -> onProgress(step.copy(phase = REMOVAL_PHASE))
                    }
                },
            ) {
                val strategies = csvImportStrategyRepository.getAllStrategies().first()
                val currencies = currencies()
                val booked = mutableListOf<Set<TransferId>>()
                val result =
                    runBulkReimport(
                        imports = run,
                        rowCount = { it.rowCount },
                        fileName = fileLabel,
                        stopRequested = stopRequested,
                        logLabel = "file",
                        onProgress = onFileProgress,
                        startDetail = "Preparing",
                        prepare = { onPrepared ->
                            // Every crypto asset the run needs exists before the first file, at the run-wide precision.
                            prepareImports(
                                run,
                                strategies,
                                currencies,
                                reads.csvImportRepository,
                                importEngine,
                                cryptoRepository,
                            ) { index, _ ->
                                onPrepared(index)
                            }
                        },
                    ) { _, file, _, reportFile ->
                        val strategy = plan.strategies[file.source]
                        when {
                            // An empty file has nothing to re-import, and (unlike a missing strategy) is not counted.
                            file.rowCount == 0 -> {
                                BulkReimportFileOutcome.NoWork
                            }

                            strategy == null -> {
                                BulkReimportFileOutcome.NoStrategy
                            }

                            else -> {
                                val imported = plan.imported.getValue(file.source)
                                val settings = ImportedSettings(imported.accountId ?: accountId, imported.currencyId ?: currencyId)
                                val booking = runStaged(file, strategy, settings, currencies, reportFile)
                                if (booking != null) booked += booking.createdTransferIds
                                BulkReimportFileOutcome.Reimported(
                                    successCount = booking?.successCount ?: 0,
                                    duplicateCount = booking?.duplicateCount ?: 0,
                                )
                            }
                        }
                    }
                if (booked.isEmpty()) {
                    result
                } else {
                    // Match once over the booked files, while the re-run still holds the reservations for settling.
                    lastFileProgress?.let { progress ->
                        val step = "Matching observations"
                        onProgress(progress.copy(detail = step, item = progress.item?.copy(step = step)))
                    }
                    val gone = importEngine.import(ImportBatch(syncMatches = true)).matchSync.deleted
                    val folded = booked.sumOf { ids -> ids.count { it in gone } }
                    result.copy(transfersCreated = result.transfersCreated - folded, duplicatesSkipped = result.duplicatesSkipped + folded)
                }
            }
        return outcome.result.copy(
            filesSkippedNoStrategy = outcome.result.filesSkippedNoStrategy + skipped,
            emptyAccountsDeleted = outcome.deletedEmptyAccounts.size,
            plan = outcome.plan,
        )
    }

    /**
     * Throws [RerunBlockedException], naming each file and why, when some file of [files] (a file, its strategy and the
     * settings it would run with) was imported but can't be imported again: its transactions would be deleted and
     * nothing would put them back.
     */
    private fun refuseUnrunnable(files: List<Triple<CsvImport, CsvImportStrategy?, ImportedSettings>>) {
        val blocked =
            files.mapNotNull { (file, strategy, settings) ->
                when {
                    file.lastAppliedAt == null -> null
                    strategy == null -> "its strategy no longer exists"
                    strategy.needsSourceAccountOverride() && settings.accountId == null -> "nothing says which account it imports onto"
                    strategy.needsCurrencyOverride() && settings.currencyId == null -> "nothing says which currency it imports in"
                    else -> null
                }?.let { reason -> "${file.originalFileName} ($reason)" }
            }
        if (blocked.isNotEmpty()) throw RerunBlockedException(blocked)
    }

    // A listed import may lack what strategy selection reads (getAllImports() doesn't populate columns).
    private suspend fun CsvImport.reloaded(): CsvImport = reads.csvImportRepository.getImport(id).first() ?: this

    private suspend fun strategyFor(
        file: CsvImport,
        strategies: List<CsvImportStrategy>,
    ): CsvImportStrategy? =
        file.lastAppliedStrategyId?.let { id -> strategies.find { it.id == id } }
            ?: strategies.selectForImport(file, reads.csvImportRepository)

    // Runs [strategy] over the file's records that are not yet imported or errored.
    private suspend fun runStaged(
        file: CsvImport,
        strategy: CsvImportStrategy,
        settings: ImportedSettings,
        currencies: List<Currency>,
        onProgress: (suspend (Progress) -> Unit)?,
    ): CsvImportResult? =
        applyStagedCsv(
            csvImport = file,
            strategy = strategy.withCurrency(settings.currencyId.takeIf { strategy.needsCurrencyOverride() }),
            sourceAccountOverride = settings.accountId,
            currencies = currencies,
            accountMappingRepository = accountMappingRepository,
            accountRepository = reads.accountRepository,
            csvImportRepository = reads.csvImportRepository,
            maintenance = maintenance,
            importEngine = importEngine,
            refreshViews = false,
            passThroughAccounts = passThroughAccountRepository.getAll().first(),
            onProgress = onProgress,
            engineBatchSize = BULK_ENGINE_BATCH_SIZE,
            cryptoRepository = cryptoRepository,
            attributeAccountMatchers = AttributeAccountMatcher.registry(accountAttributeRepository.getAll().first()),
            tradeRepository = reads.tradeRepository,
            syncMatches = false,
        )

    /**
     * Whether re-importing [file] as [plan] would run it would change nothing: its rows and every input its import
     * reads ([stagedInputsHash]) hash to what its import records hold. A file without records always runs.
     */
    private suspend fun isUnchanged(
        file: CsvImport,
        plan: ReimportAllPlan,
        accountId: AccountId?,
        currencyId: CurrencyId?,
    ): Boolean {
        val records = importRecords ?: return false
        val strategy = plan.strategies[file.source] ?: return false
        val imported = plan.imported.getValue(file.source)
        val settings = ImportedSettings(imported.accountId ?: accountId, imported.currencyId ?: currencyId)
        val effective = strategy.withCurrency(settings.currencyId.takeIf { strategy.needsCurrencyOverride() })
        val inputsHash =
            stagedInputsHash(
                strategy = effective,
                sourceAccount = effectiveSourceFor(effective, settings.accountId),
                mappings = accountMappingRepository.getAllMappings().first(),
                assetAliases = cryptoRepository.assetAliasMap(),
                attributeAccountMatchers = AttributeAccountMatcher.registry(accountAttributeRepository.getAll().first()),
                passThroughAccounts = passThroughAccountRepository.getAll().first(),
            )
        val rows = reads.csvImportRepository.getImportRows(file.id, limit = file.rowCount.coerceAtLeast(1), offset = 0)
        return isUnchanged(file, rows, records.getCsvImportRecords(file.id), inputsHash)
    }

    private suspend fun currencies(): List<Currency> = currencyRepository.getAllCurrencies().first()
}

private val REMOVAL_PHASE = Progress.Phase(1, 3, "Removing what the files created")

private fun CsvTransferWithAttributes.isIndependentBooking(): Boolean =
    attributes.isEmpty() && feeAmount == null && tradeTo == null && personalCounterpartyName == null &&
        passThrough == null && groupLeg == null && fundingMatchValue == null && unidentifiedCounterpartyAccountId == null &&
        conversion == null && discoveredMappings.isEmpty()
