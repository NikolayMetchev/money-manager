package com.moneymanager.csvimporter

import co.touchlab.kermit.Logger
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.HardCodedCurrencyMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.repository.AccountAttributeReadRepository
import com.moneymanager.domain.repository.AccountMappingReadRepository
import com.moneymanager.domain.repository.CryptoReadRepository
import com.moneymanager.domain.repository.CsvImportStrategyReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.domain.repository.PassThroughAccountReadRepository
import com.moneymanager.domain.repository.TransferSourceReadRepository
import com.moneymanager.importengineapi.CascadeRerunner
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ManualEdit
import com.moneymanager.importengineapi.RerunBlockedException
import com.moneymanager.importengineapi.RerunFile
import com.moneymanager.importengineapi.RerunLinks
import com.moneymanager.importengineapi.RerunReads
import com.moneymanager.importengineapi.captureRerunLinks
import com.moneymanager.importengineapi.executeRerun
import com.moneymanager.importengineapi.manualEditsOf
import com.moneymanager.tasks.PhasedProgress
import com.moneymanager.tasks.Progress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val logger = Logger.withTag("Reimports")

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

/**
 * What a "re-import all" will do: [files] are the files picked plus every file that depends on what they create
 * ([cascaded]), in the order they were imported.
 */
data class ReimportAllPlan(
    val files: List<CsvImport>,
    /** The files no one picked that the run re-imports because they depend on the picked ones. */
    val cascaded: Set<Source>,
    val strategies: Map<Source, CsvImportStrategy?>,
    val imported: Map<Source, ImportedSettings>,
    /** What the run deletes and imports again: every file of [files] that can be. */
    val rerunFiles: List<RerunFile>,
    /** Every hand edit the run will put back. */
    val manualEdits: List<ManualEdit>,
    val links: RerunLinks = RerunLinks(),
) {
    val skippedNoStrategy: Int get() = strategies.count { it.value == null }

    /** Whether some file needs the shared account: its strategy has none and it created nothing to read one from. */
    val needsAccount: Boolean
        get() = files.any { strategies[it.source]?.needsSourceAccountOverride() == true && imported[it.source]?.accountId == null }

    val needsCurrency: Boolean
        get() = files.any { strategies[it.source]?.needsCurrencyOverride() == true && imported[it.source]?.currencyId == null }
}

/**
 * The one way to re-import staged files (CSV, QIF, Excel or PDF), one or many: [planAll] finds every file that depends on
 * them and [reimportAll] deletes what they all created and runs their records through the normal import again, each with
 * the strategy, account and currency it was imported with. Also the [CascadeRerunner] every re-import (API
 * sessions included) re-imports the other files it takes along with, whole.
 * Each re-import holds [writeLock], the session's write lock, throughout: its delete, import, re-runs
 * and edits are separate writes, and an import between them would dedupe against a half-done state.
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
    private val writeLock: Mutex = Mutex(),
) : CascadeRerunner {
    /** Runs [block] holding the session's write lock, for the re-imports that run outside this class (API sessions). */
    suspend fun <T> exclusively(block: suspend () -> T): T = writeLock.withLock { block() }

    /**
     * Plans a "re-import all" over [files]: one query finds every other file that depends on what they create,
     * transitively ([TransferSourceReadRepository.getRerunClosure]), and the run re-imports all of them, in the
     * order they were imported. What each file created is read before any of it is deleted. [onProgress] counts
     * files read.
     */
    suspend fun planAll(
        files: List<CsvImport>,
        onProgress: (suspend (Progress) -> Unit)? = null,
    ): ReimportAllPlan {
        val progress = PhasedProgress(onProgress)
        val strategies = csvImportStrategyRepository.getAllStrategies().first()
        progress.emit(base = 0f, detail = "Finding the files that depend on them")
        // A file with nothing to run over is left alone, unless another file's re-import takes it along.
        val runnable = files.filter { it.rowCount > 0 && strategyFor(it.reloaded(), strategies) != null }
        val closure =
            withContext(Dispatchers.Default) {
                reads.transferSourceRepository.getRerunClosure(runnable.mapTo(mutableSetOf()) { it.id })
            }
        val selected = files.mapTo(mutableSetOf()) { it.id }
        // Each file is read once, all of them at once (reads don't block one another), off the caller's
        // dispatcher (a dialog's is the UI thread); the hand edits across them all are then loaded in one go.
        val progressLock = Mutex()
        var filesRead = 0
        // The caller's files first: files imported at the same instant keep the order they were given.
        val ids = files.map { it.id } + closure.filterNot { it in selected }
        val count = ids.size
        val fileReads =
            withContext(Dispatchers.Default) {
                ids
                    .map { id ->
                        async {
                            val file = reads.csvImportRepository.getImport(id).first()
                            val read =
                                file?.takeIf { id in closure }?.let {
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
                                    base = PLAN_ALL_CLOSURE_SPAN + PLAN_ALL_READ_SPAN * filesRead / count,
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
        val editsBase = PLAN_ALL_CLOSURE_SPAN + PLAN_ALL_READ_SPAN
        progress.emit(base = editsBase, detail = "Reading manual edits")
        val manualEdits =
            manualEditsOf(rerunFiles.associate { it.file to it.createdTransfers }, reads).values.flatten()
        return ReimportAllPlan(
            files = loaded,
            cascaded = loaded.filter { it.id in closure && it.id !in selected }.mapTo(mutableSetOf()) { it.source },
            strategies = loaded.associate { it.source to strategyFor(it, strategies) },
            imported = loaded.associate { it.source to it.importedSettings },
            rerunFiles = rerunFiles,
            manualEdits = manualEdits,
            links = captureRerunLinks(rerunFiles, reads),
        )
    }

    /**
     * Re-imports every file of [plan]: one batch deletes what they all created and resets their rows, then each
     * file is imported again in the order it was first imported, as a fresh import of its rows, and finally the
     * hand edits are put back, the empty accounts deleted and the materialized views refreshed, once each.
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

    @Suppress("LongParameterList")
    private suspend fun reimportAllLocked(
        plan: ReimportAllPlan,
        accountId: AccountId?,
        currencyId: CurrencyId?,
        onProgress: suspend (Progress) -> Unit,
        fileLabel: (CsvImport) -> String,
        stopRequested: () -> Boolean,
    ): BulkReimportResult {
        val run = plan.files.filter { file -> plan.rerunFiles.any { it.file == file.source } }
        refuseUnrunnable(
            run.map { file ->
                val imported = plan.imported.getValue(file.source)
                Triple(file, plan.strategies[file.source], ImportedSettings(imported.accountId ?: accountId, imported.currencyId ?: currencyId))
            },
        )
        val skipped = plan.files.count { it.rowCount > 0 && plan.strategies[it.source] == null && it !in run }
        val rerun = mutableSetOf<Source>()
        val reimported = mutableSetOf<Source>()
        // Three phases, each filling the bar from empty: the one delete batch, then runBulkReimport's two (preparing
        // the files, re-importing them). The steps after the last file (hand edits, empty accounts, views) keep the
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
                manualEdits = plan.manualEdits,
                links = plan.links,
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
                        prepareImports(run, strategies, currencies, reads.csvImportRepository, importEngine, cryptoRepository) { index, _ ->
                            onPrepared(index)
                        }
                    },
                ) { _, file, _, onFileProgress ->
                    val strategy = plan.strategies[file.source]
                    when {
                        // An empty file has nothing to re-import, and (unlike a missing strategy) is not counted.
                        file.rowCount == 0 -> {
                            rerun += file.source
                            BulkReimportFileOutcome.NoWork
                        }

                        strategy == null -> {
                            BulkReimportFileOutcome.NoStrategy
                        }

                        else -> {
                            val imported = plan.imported.getValue(file.source)
                            val settings = ImportedSettings(imported.accountId ?: accountId, imported.currencyId ?: currencyId)
                            val result = runStaged(file, strategy, settings, currencies, onFileProgress)
                            rerun += file.source
                            reimported += file.source
                            BulkReimportFileOutcome.Reimported(
                                successCount = result?.successCount ?: 0,
                                duplicateCount = result?.duplicateCount ?: 0,
                            )
                        }
                    }
                }
            }
        val cascadedRerun = plan.cascaded.count { it in rerun }
        return outcome.result.copy(
            // The files picked; those taken along are counted on their own.
            filesImported = outcome.result.filesImported - plan.cascaded.count { it in reimported },
            filesSkippedNoStrategy = outcome.result.filesSkippedNoStrategy + skipped,
            emptyAccountsDeleted = outcome.deletedEmptyAccounts.size,
            cascadedFiles = cascadedRerun,
            cascadedFilesNotRerun = plan.cascaded.size - cascadedRerun,
            reappliedEdits = outcome.reappliedEdits.size,
            unmatchedEdits = outcome.unmatchedEdits,
            reappliedLinks = outcome.reappliedLinks,
        )
    }

    /** Imports the [files] a re-import cascaded into again, whole and in order, each as it was last imported. */
    override suspend fun prepare(files: List<Source>): suspend (onProgress: (suspend (Progress) -> Unit)?) -> Set<Source> {
        val strategies = csvImportStrategyRepository.getAllStrategies().first()
        val imports =
            files.mapNotNull { file -> (file as? Source.Csv)?.let { reads.csvImportRepository.getImport(it.importId).first() } }.map { file ->
                file to strategyFor(file, strategies)
            }
        // Nobody is asked about a file taken along, so one that can't run stops the re-import before it deletes anything.
        refuseUnrunnable(imports.map { (file, strategy) -> Triple(file, strategy, file.importedSettings) })
        return { onProgress ->
            val currencies = currencies()
            val notRerun = files.toMutableSet()
            imports.forEach { (file, strategy) ->
                // One file failing leaves it to its next import rather than failing the re-import that took it
                // along, whose own work is already done.
                try {
                    if (strategy != null) {
                        runStaged(file, strategy, file.importedSettings, currencies, onProgress)
                        notRerun -= file.source
                    }
                } catch (expected: CancellationException) {
                    throw expected
                } catch (expected: Exception) {
                    logger.e(expected) { "Re-importing ${file.originalFileName} along with another re-import failed" }
                }
            }
            notRerun
        }
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
        )

    private suspend fun currencies(): List<Currency> = currencyRepository.getAllCurrencies().first()
}

private val REMOVAL_PHASE = Progress.Phase(1, 3, "Removing what the files created")

// Reading each file is nearly all of "re-import all" planning; finding them and their hand edits are the rest.
private const val PLAN_ALL_CLOSURE_SPAN = 0.05f
private const val PLAN_ALL_READ_SPAN = 0.85f
