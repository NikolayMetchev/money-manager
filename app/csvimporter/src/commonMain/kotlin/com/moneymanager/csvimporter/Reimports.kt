package com.moneymanager.csvimporter

import co.touchlab.kermit.Logger
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
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
import com.moneymanager.importengineapi.AffectedRecords
import com.moneymanager.importengineapi.AffectedRecordsRerunner
import com.moneymanager.importengineapi.CsvImportMutation
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ManualEdit
import com.moneymanager.importengineapi.RerunReads
import com.moneymanager.importengineapi.SourceRerunPlan
import com.moneymanager.importengineapi.SourceRerunResult
import com.moneymanager.importengineapi.executeSourceRerun
import com.moneymanager.importengineapi.manualEditsOf
import com.moneymanager.importengineapi.planSourceRerun
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
 * The account and currency a file was imported with, read from the transactions it created, so a
 * re-import doesn't ask again: [accountId] is the one account every one of them moves money in or out
 * of (null when none or several do), [currencyId] their most common currency.
 */
data class ImportedSettings(
    val accountId: AccountId?,
    val currencyId: CurrencyId?,
)

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

/** What re-importing one [file] will do; [needsAccount] and [needsCurrency] say what the person must pick. */
data class FileReimportPlan(
    val file: CsvImport,
    /** The strategy it was last imported with, else the one its content selects; null when neither exists. */
    val strategy: CsvImportStrategy?,
    val imported: ImportedSettings,
    val rerun: SourceRerunPlan,
) {
    val needsAccount: Boolean get() = strategy?.needsSourceAccountOverride() == true && imported.accountId == null
    val needsCurrency: Boolean get() = strategy?.needsCurrencyOverride() == true && imported.currencyId == null
}

/** What a "re-import all" over [files] will do. */
data class ReimportAllPlan(
    val files: List<CsvImport>,
    val strategies: Map<Source, CsvImportStrategy?>,
    val imported: Map<Source, ImportedSettings>,
    /** Every hand edit the run will put back. */
    val manualEdits: List<ManualEdit>,
) {
    val skippedNoStrategy: Int get() = strategies.count { it.value == null }

    /** Whether some file needs the shared account: its strategy has none and it created nothing to read one from. */
    val needsAccount: Boolean
        get() = files.any { strategies[it.source]?.needsSourceAccountOverride() == true && imported[it.source]?.accountId == null }

    val needsCurrency: Boolean
        get() = files.any { strategies[it.source]?.needsCurrencyOverride() == true && imported[it.source]?.currencyId == null }
}

/**
 * The one way to re-import a staged file (CSV, QIF, Excel or PDF), one at a time or all at once: what the file created is
 * deleted and its records run through the normal import again, with the strategy, account and currency
 * it was imported with ([executeSourceRerun]). Also the [AffectedRecordsRerunner] every re-import (API
 * sessions included) hands the other files' records to that were matched against a deleted transaction.
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
) : AffectedRecordsRerunner {
    /** Runs [block] holding the session's write lock, for the re-imports that run outside this class (API sessions). */
    suspend fun <T> exclusively(block: suspend () -> T): T = writeLock.withLock { block() }

    /**
     * Plans re-importing [file]: what it created, the other files matched against that, and its hand edits.
     * On a large database this takes a while, so [onProgress] hears each step.
     */
    suspend fun plan(
        file: CsvImport,
        onProgress: (suspend (Progress) -> Unit)? = null,
    ): FileReimportPlan {
        val progress = PhasedProgress(onProgress)
        progress.emit(base = 0f, detail = "Loading ${file.originalFileName}")
        val loaded = file.reloaded()
        val strategy = strategyFor(loaded, csvImportStrategyRepository.getAllStrategies().first())
        progress.emit(base = PLAN_LOAD_SPAN, detail = READING_IMPORT_SETTINGS)
        val imported = importedSettings(loaded.source)
        return FileReimportPlan(
            file = loaded,
            strategy = strategy,
            imported = imported,
            rerun = rerunPlan(loaded, progress.sink(base = PLAN_SETTINGS_END, span = 1f - PLAN_SETTINGS_END)),
        )
    }

    /**
     * Re-imports per [plan]. [accountId] and [currencyId] are used only where the file's own
     * transactions don't say ([FileReimportPlan.needsAccount], [FileReimportPlan.needsCurrency]).
     */
    suspend fun reimport(
        plan: FileReimportPlan,
        accountId: AccountId?,
        currencyId: CurrencyId?,
        onProgress: (suspend (Progress) -> Unit)? = null,
        refreshViews: Boolean = true,
    ): SourceRerunResult<CsvImportResult?> = writeLock.withLock { rerunFile(plan, accountId, currencyId, onProgress, refreshViews) }

    private suspend fun rerunFile(
        plan: FileReimportPlan,
        accountId: AccountId?,
        currencyId: CurrencyId?,
        onProgress: (suspend (Progress) -> Unit)?,
        refreshViews: Boolean,
    ): SourceRerunResult<CsvImportResult?> {
        val strategy = requireNotNull(plan.strategy) { "No strategy matches ${plan.file.originalFileName}" }
        val settings =
            ImportedSettings(plan.imported.accountId ?: accountId, plan.imported.currencyId ?: currencyId)
        return importEngine.executeSourceRerun(
            plan = plan.rerun,
            resetRecords = resetBatch(plan.file),
            reads = reads,
            maintenance = maintenance,
            rerunAffected = this,
            onProgress = onProgress,
            refreshViews = refreshViews,
        ) {
            runStaged(plan.file, strategy, settings, currencies(), onProgress)
        }
    }

    /**
     * Plans a "re-import all" over [files], reading each file's settings before any of them is deleted.
     * [onProgress] gives each file an equal slice of the bar.
     */
    suspend fun planAll(
        files: List<CsvImport>,
        onProgress: (suspend (Progress) -> Unit)? = null,
    ): ReimportAllPlan {
        val progress = PhasedProgress(onProgress)
        val strategies = csvImportStrategyRepository.getAllStrategies().first()
        val prefixes = files.mapIndexed { index, file -> "File ${index + 1} of ${files.size}: ${file.originalFileName}" }
        // Each file is read once, all of them at once (reads don't block one another), off the caller's
        // dispatcher (a dialog's is the UI thread); what spans them all (their transfers, the hand edits) is
        // then loaded in one go.
        val progressLock = Mutex()
        var filesRead = 0
        val fileReads =
            withContext(Dispatchers.Default) {
                files
                    .map { file ->
                        async {
                            val reloaded = file.reloaded()
                            val read =
                                FileReads(
                                    file = reloaded,
                                    created = reads.transferSourceRepository.getTransfersCreatedBy(reloaded.source),
                                    referenced = referencedTransferIds(reloaded.source),
                                )
                            // Files finish in any order, so the bar counts files read rather than following the list.
                            progressLock.withLock {
                                filesRead++
                                progress.emit(
                                    base = PLAN_ALL_READ_SPAN * filesRead / files.size,
                                    detail = "File $filesRead of ${files.size}: ${file.originalFileName} — $READING_IMPORT_SETTINGS",
                                )
                            }
                            read
                        }
                    }.awaitAll()
            }
        val loaded = fileReads.map { it.file }
        val created = fileReads.associate { it.file.source to it.created }
        val imported = importedSettingsOf(created, fileReads.associate { it.file.source to it.referenced })
        val indexOf = loaded.withIndex().associate { (index, file) -> file.source to index }
        val manualEdits =
            manualEditsOf(created, reads) { source, inner ->
                val index = indexOf.getValue(source)
                progress.emit(
                    base = PLAN_ALL_READ_SPAN + (1f - PLAN_ALL_READ_SPAN) * index / files.size,
                    span = (1f - PLAN_ALL_READ_SPAN) / files.size,
                    detail = "${prefixes[index]} — ${inner.detail}",
                    innerFraction = inner.fraction,
                    processed = inner.processed,
                    total = inner.total,
                )
            }
        return ReimportAllPlan(
            files = loaded,
            strategies = loaded.associate { it.source to strategyFor(it, strategies) },
            imported = imported,
            manualEdits = manualEdits.values.flatten(),
        )
    }

    /**
     * Re-imports every file of [plan], one after another, refreshing materialized views once at the end.
     * [accountId] and [currencyId] are for the files whose own transactions don't say.
     */
    suspend fun reimportAll(
        plan: ReimportAllPlan,
        accountId: AccountId?,
        currencyId: CurrencyId?,
        onProgress: suspend (Progress) -> Unit,
    ): BulkReimportResult = writeLock.withLock { reimportAllLocked(plan, accountId, currencyId, onProgress) }

    private suspend fun reimportAllLocked(
        plan: ReimportAllPlan,
        accountId: AccountId?,
        currencyId: CurrencyId?,
        onProgress: suspend (Progress) -> Unit,
    ): BulkReimportResult =
        runBulkReimport(
            imports = plan.files,
            rowCount = { it.rowCount },
            fileName = { it.originalFileName },
            logLabel = "file",
            maintenance = maintenance,
            onProgress = onProgress,
            startDetail = "Preparing",
            prepare = {
                // Every crypto asset the run needs exists before the first file, at the run-wide precision.
                ensureCryptoAssetsForImports(
                    plan.files,
                    csvImportStrategyRepository.getAllStrategies().first(),
                    currencies(),
                    reads.csvImportRepository,
                    importEngine,
                    cryptoRepository,
                )
            },
        ) { _, file, _, onFileProgress ->
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
                    val filePlan =
                        FileReimportPlan(file, strategy, plan.imported.getValue(file.source), rerunPlan(file))
                    val result = rerunFile(filePlan, accountId, currencyId, onFileProgress, refreshViews = false)
                    BulkReimportFileOutcome.Reimported(
                        result = result,
                        successCount = result.rerun?.successCount ?: 0,
                        duplicateCount = result.rerun?.duplicateCount ?: 0,
                    )
                }
            }
        }

    /** Re-runs the reset records of the files in [affected], each as it was imported. */
    override suspend fun prepare(affected: AffectedRecords): suspend (onProgress: (suspend (Progress) -> Unit)?) -> AffectedRecords {
        val files =
            affected.byFile.keys.mapNotNull { file ->
                (file as? Source.Csv)?.let { reads.csvImportRepository.getImport(it.importId).first() }
            }
        // Read now: once the re-import resets these records, a file that only matched others' transactions
        // has nothing left to read its account from.
        val settings = importedSettingsOf(files.map { it.source })
        return { onProgress ->
            val strategies = csvImportStrategyRepository.getAllStrategies().first()
            val currencies = currencies()
            val notRerun = mutableSetOf<Source>()
            files.forEach { file ->
                // One file failing leaves its reset records to its next import rather than failing the
                // re-import that reset them, whose own work is already done.
                try {
                    val strategy = strategyFor(file, strategies)
                    val fileSettings = settings.getValue(file.source)
                    // Without the account or currency nobody is asked here, so the records wait for the file's next import.
                    if (strategy == null ||
                        (strategy.needsSourceAccountOverride() && fileSettings.accountId == null) ||
                        (strategy.needsCurrencyOverride() && fileSettings.currencyId == null)
                    ) {
                        notRerun += file.source
                    } else {
                        runStaged(file, strategy, fileSettings, currencies, onProgress)
                    }
                } catch (expected: CancellationException) {
                    throw expected
                } catch (expected: Exception) {
                    logger.e(
                        expected,
                    ) { "Re-running the records of ${file.originalFileName} matched against a re-imported transaction failed" }
                    notRerun += file.source
                }
            }
            AffectedRecords(affected.byFile.filterKeys { it in notRerun })
        }
    }

    /**
     * Reads the account and currency [source] was imported with; see [ImportedSettings]. Rows that matched
     * another file's transaction as duplicates count too, so a file that created nothing still knows them.
     */
    suspend fun importedSettings(source: Source): ImportedSettings = importedSettingsOf(listOf(source)).getValue(source)

    private suspend fun importedSettingsOf(sources: List<Source>): Map<Source, ImportedSettings> =
        importedSettingsOf(
            created = sources.associateWith { reads.transferSourceRepository.getTransfersCreatedBy(it) },
            referenced = sources.associateWith { referencedTransferIds(it) },
        )

    /**
     * [importedSettings] for each source of [created] (the transfers it created; [referenced], those its
     * records point at). Their transfers are loaded together: one batched load, not one per file.
     */
    private suspend fun importedSettingsOf(
        created: Map<Source, Map<TransferId, Source>>,
        referenced: Map<Source, List<TransferId>>,
    ): Map<Source, ImportedSettings> {
        val ids = created.mapValues { (source, transfers) -> transfers.keys + referenced[source].orEmpty() }
        val transfers = reads.transactionRepository.getTransactionsByIds(ids.values.flatten().toSet())
        return ids.mapValues { (_, fileIds) -> settingsOf(fileIds.mapNotNull(transfers::get)) }
    }

    // The transfers a file's records point at, its duplicates of other files' transfers included.
    private suspend fun referencedTransferIds(source: Source): List<TransferId> =
        when (source) {
            is Source.Csv -> {
                reads.csvImportRepository.getImportRows(source.importId, limit = Int.MAX_VALUE, offset = 0).mapNotNull { it.transferId }
            }

            else -> {
                emptyList()
            }
        }

    private fun settingsOf(transfers: List<Transfer>): ImportedSettings {
        if (transfers.isEmpty()) return ImportedSettings(null, null)
        val sharedAccount =
            transfers
                .map { setOf(it.sourceAccountId, it.targetAccountId) }
                .reduce { shared, accounts -> shared intersect accounts }
                .singleOrNull()
        val currency =
            transfers
                .mapNotNull { it.amount.asset as? Currency }
                .groupingBy { it.id }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key
        return ImportedSettings(sharedAccount, currency)
    }

    private suspend fun rerunPlan(
        file: CsvImport,
        onProgress: (suspend (Progress) -> Unit)? = null,
    ): SourceRerunPlan =
        planSourceRerun(
            source = file.source,
            tradeIds = reads.csvImportRepository.getTradeIdsCreatedByImport(file.id),
            createdAccountIds = reads.csvImportRepository.getAccountsCreatedByImport(file.id),
            reads = reads,
            onProgress = onProgress,
        )

    // Returns every record to never-imported, keeping the file's import history.
    private fun resetBatch(file: CsvImport): ImportBatch =
        ImportBatch(csvImportMutations = listOf(CsvImportMutation.ResetRowStatuses(file.id, null)))

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

private const val READING_IMPORT_SETTINGS = "Reading import settings"
private const val PLAN_LOAD_SPAN = 0.02f
private const val PLAN_SETTINGS_END = 0.1f

// Reading each file is nearly all of "re-import all" planning; its hand edits are the rest.
private const val PLAN_ALL_READ_SPAN = 0.9f

/** What "re-import all" planning reads from one [file]: the transfers it created and those its records point at. */
private class FileReads(
    val file: CsvImport,
    val created: Map<TransferId, Source>,
    val referenced: List<TransferId>,
)
