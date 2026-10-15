package com.moneymanager.importdirectory

import com.moneymanager.csvimporter.AttributeAccountMatcher
import com.moneymanager.csvimporter.BulkApplyResult
import com.moneymanager.csvimporter.BulkFileStatus
import com.moneymanager.csvimporter.BulkReimportResult
import com.moneymanager.csvimporter.DirectoryRedownloadResult
import com.moneymanager.csvimporter.STRATEGY_CONTENT_SAMPLE_SIZE
import com.moneymanager.csvimporter.ScanResult
import com.moneymanager.csvimporter.bulkApplyCsv
import com.moneymanager.csvimporter.bulkReimportCsv
import com.moneymanager.csvimporter.discoverImportableFolders
import com.moneymanager.csvimporter.findUnmatchedFundingReferences
import com.moneymanager.csvimporter.needsSourceAccountOverride
import com.moneymanager.csvimporter.redownloadImportDirectory
import com.moneymanager.csvimporter.scanImportDirectory
import com.moneymanager.csvimporter.selectForCsv
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.ImportDirectoryId
import com.moneymanager.domain.model.QifImportId
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.importdirectory.ImportDirectory
import com.moneymanager.domain.model.qif.QifImport
import com.moneymanager.domain.repository.AccountAttributeReadRepository
import com.moneymanager.domain.repository.AccountMappingReadRepository
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CryptoReadRepository
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.domain.repository.CsvImportStrategyReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.domain.repository.ImportDirectoryReadRepository
import com.moneymanager.domain.repository.PassThroughAccountReadRepository
import com.moneymanager.domain.repository.QifImportReadRepository
import com.moneymanager.domain.repository.SettingsReadRepository
import com.moneymanager.domain.repository.TradeReadRepository
import com.moneymanager.domain.repository.TransactionReadRepository
import com.moneymanager.domain.repository.TransferRelationshipReadRepository
import com.moneymanager.domain.repository.TransferSourceReadRepository
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.createImportDirectory
import com.moneymanager.importengineapi.setLastQifAccount
import com.moneymanager.importfilesource.ImportFileSourceFactory
import com.moneymanager.qifimporter.QifCsvAdapter
import com.moneymanager.qifimporter.bulkApplyQif
import com.moneymanager.qifimporter.bulkReimportQif
import com.moneymanager.qifimporter.qifCompatible
import com.moneymanager.qifimporter.selectForQifContent
import com.moneymanager.tasks.Progress
import com.moneymanager.tasks.TaskScope
import com.moneymanager.tasks.formatElapsedTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

/**
 * Everything import directories do beyond plain CRUD: downloading folders (with subfolder discovery),
 * re-downloading them, and planning + applying CSV/QIF "import all" and "re-import all". The desktop app,
 * Android and the CLI all run this, so the screens only start tasks and render plans.
 *
 * Directory runs report through the [TaskScope] they're given, one child task per folder, so whoever
 * starts them (a screen, the CLI) picks the parent and sees the same progress and issues. Folders are
 * listed and downloaded concurrently, but the engine doesn't serialize writes, so every run holds
 * [writeLock] around its writes. Share one lock per database session, with `ApiImportService` too when
 * both run at once.
 *
 * [fileSourceFactory] opens a folder's files; null (no file access on this platform) makes no folder
 * scannable. LOCAL folders only scan on the device that added them ([deviceId]).
 */
@Suppress("LongParameterList", "TooManyFunctions")
class ImportDirectoryService(
    private val importDirectoryRepository: ImportDirectoryReadRepository,
    private val csvImportRepository: CsvImportReadRepository,
    private val qifImportRepository: QifImportReadRepository,
    private val csvImportStrategyRepository: CsvImportStrategyReadRepository,
    private val accountMappingRepository: AccountMappingReadRepository,
    private val accountRepository: AccountReadRepository,
    private val accountAttributeRepository: AccountAttributeReadRepository,
    private val currencyRepository: CurrencyReadRepository,
    private val cryptoRepository: CryptoReadRepository,
    private val tradeRepository: TradeReadRepository,
    private val passThroughAccountRepository: PassThroughAccountReadRepository,
    private val settingsRepository: SettingsReadRepository,
    private val transactionRepository: TransactionReadRepository,
    private val transferRelationshipRepository: TransferRelationshipReadRepository,
    private val transferSourceRepository: TransferSourceReadRepository,
    private val maintenance: Maintenance,
    private val importEngine: ImportEngine,
    private val fileSourceFactory: ImportFileSourceFactory?,
    private val deviceId: DeviceId,
    private val writeLock: Mutex = Mutex(),
) {
    /** Whether [directory] can be downloaded here: there is file access, and a LOCAL folder belongs to this device. */
    fun isScannable(directory: ImportDirectory): Boolean = fileSourceFactory != null && directory.isOn(deviceId)

    /**
     * The folders a re-download of [directory] covers: itself, plus its discovered subfolders when it is a
     * top-level folder ([all] is every directory). Excluded and unscannable folders are left out, as for download.
     */
    fun redownloadScope(
        directory: ImportDirectory,
        all: List<ImportDirectory>,
    ): List<ImportDirectory> =
        (listOf(directory) + all.filter { it.parentId == directory.id })
            .filter { isScannable(it) && !it.excluded }

    /**
     * The included folders [roots] cover here: each root plus its subfolders, or every scannable included
     * folder when [roots] is null.
     */
    fun scope(
        roots: List<ImportDirectory>?,
        all: List<ImportDirectory>,
    ): List<ImportDirectory> {
        val rootIds = roots?.mapTo(mutableSetOf()) { it.id }
        return all.filter { dir ->
            isScannable(dir) && !dir.excluded && (rootIds == null || dir.id in rootIds || dir.parentId in rootIds)
        }
    }

    /**
     * Downloads every included folder at once (only [roots] and their subfolders, when given). Known
     * folders start straight away; subfolders discovered under the top-level folders start as soon as they
     * are found, so the overall total grows over the run. Each folder and each subfolder search is a child
     * of [task]; every failure is reported on [task] as an issue, and one folder failing never stops the others.
     */
    @Suppress("LongMethod")
    suspend fun downloadAll(
        task: TaskScope,
        roots: List<ImportDirectory>? = null,
    ): DownloadAllResult {
        val startedAt = TimeSource.Monotonic.markNow()
        try {
            val directories = importDirectoryRepository.getAllDirectories().first()
            val inScope = scope(roots, directories)
            val dirsByRef = directories.associateByTo(mutableMapOf()) { it.folderRef }
            val results = mutableListOf<Pair<ImportDirectory, ScanResult>>()
            val folderFailures = mutableListOf<String>()
            val resultsLock = Mutex()

            // One folder failing must not cancel the rest of the run, so each one's error is recorded
            // against it instead of propagating to the shared scope.
            suspend fun <T> containingFailure(
                dir: ImportDirectory,
                what: String,
                fallback: T,
                block: suspend () -> T,
            ): T =
                try {
                    block()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (expected: Exception) {
                    resultsLock.withLock { folderFailures += "${dir.name} — couldn't $what: ${expected.message}" }
                    fallback
                }
            val created =
                coroutineScope {
                    val runScope = this

                    fun startDownload(dir: ImportDirectory) {
                        runScope.launch(Dispatchers.IO) {
                            // The child task is marked failed; the run records it and moves on.
                            containingFailure(dir, "download this folder", Unit) {
                                val result =
                                    task.child(
                                        folderDownloadTaskKey(dir.id),
                                        dir.name,
                                        "Listing files…",
                                    ) { downloadFolder(this, dir) }
                                resultsLock.withLock { results += dir to result }
                            }
                        }
                    }

                    inScope.forEach(::startDownload)
                    inScope
                        .filter { it.topLevel }
                        .map { root ->
                            async(Dispatchers.IO) {
                                task.child(subfolderSearchTaskKey(root.id), "Subfolders of ${root.name}", "Searching…") {
                                    containingFailure(root, "search its subfolders", 0) {
                                        createSubdirectories(root, dirsByRef, ::startDownload)
                                    }
                                }
                            }
                        }.awaitAll()
                        .sum()
                }
            val result = DownloadAllResult(created, results.toList(), folderFailures.toList(), startedAt.elapsedNow())
            result.issues.forEach(task::reportIssue)
            return result
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (expected: Exception) {
            // Reworded so the failed task's detail says how far the run got.
            throw IllegalStateException(
                "Download all failed after ${formatElapsedTime(startedAt.elapsedNow())} — ${expected.message}",
                expected,
            )
        }
    }

    /**
     * Downloads one folder's own importable files as a child of [task]. A top-level folder also searches
     * for subfolders at the same time and creates a directory for each new one, without downloading them.
     */
    suspend fun downloadDirectory(
        task: TaskScope,
        directory: ImportDirectory,
    ): DirectoryDownloadResult =
        try {
            coroutineScope {
                val created =
                    async(Dispatchers.IO) {
                        if (directory.topLevel) {
                            // A failed search is reported without cancelling the folder's own download.
                            try {
                                task.child(subfolderSearchTaskKey(directory.id), "Subfolders of ${directory.name}", "Searching…") {
                                    val known = importDirectoryRepository.getAllDirectories().first()
                                    createSubdirectories(directory, known.associateByTo(mutableMapOf()) { it.folderRef })
                                }
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (expected: Exception) {
                                task.reportIssue("${directory.name} — couldn't search its subfolders: ${expected.message}")
                                null
                            }
                        } else {
                            null
                        }
                    }
                val scan =
                    withContext(Dispatchers.IO) {
                        task.child(
                            folderDownloadTaskKey(directory.id),
                            directory.name,
                            "Listing files…",
                        ) { downloadFolder(this, directory) }
                    }
                DirectoryDownloadResult(directory, scan, created.await()).also { result ->
                    result.scan.failures.forEach(task::reportIssue)
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (expected: Exception) {
            throw IllegalStateException("${directory.name}: failed — ${expected.message}", expected)
        }

    /**
     * Re-downloads every staged CSV in [folders], one folder at a time (each a child of [task]), and
     * re-stages each file in place with the current parser. [unimport], when given, unimports a file whose
     * imported rows the new copy no longer has instead of skipping it. A folder that fails is recorded and
     * the rest carry on; the result's [issues] are reported on [task].
     */
    suspend fun redownload(
        task: TaskScope,
        folders: List<ImportDirectory>,
        unimport: (suspend (CsvImportId) -> Unit)?,
    ): DirectoryRedownloadResult {
        var result = DirectoryRedownloadResult()
        for (folder in folders) {
            result +=
                try {
                    task.child(folderDownloadTaskKey(folder.id), folder.name, "Listing files…") {
                        withContext(Dispatchers.IO) {
                            writeLock.withLock {
                                redownloadImportDirectory(
                                    directory = folder,
                                    fileSource = requireFileSourceFactory().create(folder),
                                    importDirectoryRepository = importDirectoryRepository,
                                    csvImportRepository = csvImportRepository,
                                    importEngine = importEngine,
                                    unimport = unimport,
                                    onProgress = { done, total -> report(Progress.counted("$done / $total files", done, total)) },
                                )
                            }
                        }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (expected: Exception) {
                    DirectoryRedownloadResult(failures = listOf("${folder.name}: ${expected.message}"))
                }
        }
        result.issues.forEach(task::reportIssue)
        return result
    }

    /**
     * Downloads every folder, then applies every unimported CSV and QIF file that needs no decision, each
     * phase a child of [task]. Files that need a person (no matching strategy, a source account nobody set)
     * are left staged and returned in [ImportAllResult.needsAttention], also reported as issues on [task],
     * with the card references the applied files carry that no account owns.
     *
     * [directories] limits the run to those folders and their subfolders. [redownloadFirst] first re-stages the
     * files already staged from them ([redownload], never unimporting; a file that would need it is left
     * for a person). [apply] false stops after downloading. [refreshViews] false leaves the materialized
     * views to a caller that batches several runs.
     */
    suspend fun importAll(
        task: TaskScope,
        directories: List<ImportDirectory>? = null,
        apply: Boolean = true,
        redownloadFirst: Boolean = false,
        refreshViews: Boolean = true,
    ): ImportAllResult {
        val redownloaded =
            if (redownloadFirst) {
                val folders = scope(directories, importDirectoryRepository.getAllDirectories().first())
                task.child("$IMPORT_ALL_TASK_KEY-redownload", "Re-download", "Listing files…") {
                    redownload(this, folders, unimport = null)
                }
            } else {
                null
            }
        val redownloadAttention =
            redownloaded?.blocked.orEmpty().map { NeedsAttention(it, NeedsAttentionReason.REDOWNLOAD_BLOCKED) } +
                redownloaded?.reimportSuggested.orEmpty().map { NeedsAttention(it, NeedsAttentionReason.REIMPORT_SUGGESTED) }
        val download = task.child(DOWNLOAD_ALL_TASK_KEY, "Download all", "Listing folders.") { downloadAll(this, directories) }
        if (!apply) {
            return ImportAllResult(download, BulkApplyResult(emptyList()), BulkApplyResult(emptyList()), redownloadAttention, redownloaded)
        }
        val files = directories?.let { filesIn(scope(it, importDirectoryRepository.getAllDirectories().first())) }
        val csvPlan: CsvImportAllPlan
        val qifPlan: QifImportAllPlan
        val csv: BulkApplyResult
        val qif: BulkApplyResult
        try {
            csvPlan = planCsvImportAll(files?.let { ids -> unimportedCsvFiles().filter { it.id.toString() in ids } })
            csv =
                task.child("${IMPORT_ALL_TASK_KEY}-csv", "Import CSV files", "Preparing") {
                    applyCsvImportAll(csvPlan.unattended(), sourceAccountOverride = null, onProgress = ::report, refreshViews = false)
                }
            qifPlan = planQifImportAll(files?.let { ids -> unimportedQifFiles().filter { it.id.toString() in ids } })
            qif =
                task.child("${IMPORT_ALL_TASK_KEY}-qif", "Import QIF files", "Preparing") {
                    applyQifImportAll(qifPlan.unattended(), sourceAccountId = null, onProgress = ::report, refreshViews = false)
                }
        } finally {
            // Whatever the CSV phase wrote must show up even when the QIF phase fails or the run is cancelled.
            if (refreshViews) withContext(NonCancellable) { maintenance.refreshMaterializedViews() }
        }
        val needsAttention =
            csvPlan.needsAttention + qifPlan.needsAttention + redownloadAttention + unassignedCards(csv)
        needsAttention.forEach { task.reportIssue("${it.subject}: ${it.reason.description}") }
        return ImportAllResult(download, csv, qif, needsAttention, redownloaded)
    }

    // The CSV and QIF import ids (as strings, the two id types differ) staged from [folders].
    private suspend fun filesIn(folders: List<ImportDirectory>): Set<String> =
        folders
            .flatMap { importDirectoryRepository.getTrackedFiles(it.id).first() }
            .mapNotNullTo(mutableSetOf()) { file -> (file.csvImportId ?: file.qifImportId)?.toString() }

    // Card references in the files [csv] imported that no account owns, as the Card Last-4 tab finds them.
    private suspend fun unassignedCards(csv: BulkApplyResult): List<NeedsAttention> {
        val imported = csv.files.filter { it.status == BulkFileStatus.IMPORTED }.mapTo(mutableSetOf()) { it.fileId }
        if (imported.isEmpty()) return emptyList()
        return findUnmatchedFundingReferences(
            imports = csvImportRepository.getAllImports().first().filter { it.id.toString() in imported },
            strategies = csvImportStrategyRepository.getAllStrategies().first(),
            csvImportRepository = csvImportRepository,
            attributeAccountMatchers = AttributeAccountMatcher.registry(accountAttributeRepository.getAll().first()),
        ).map { NeedsAttention(it.value, NeedsAttentionReason.UNASSIGNED_CARD) }
    }

    /**
     * Plans a CSV "import all" over [imports] (every unimported file when null): matches each file's
     * strategy from its name, columns and sampled rows, and finds the files a person must resolve.
     */
    suspend fun planCsvImportAll(imports: List<CsvImport>? = null): CsvImportAllPlan {
        val files = imports ?: unimportedCsvFiles()
        val strategies = csvImportStrategyRepository.getAllStrategies().first()
        val directoryAccounts = importDirectoryRepository.csvImportSourceAccounts()
        val matches = matchCsvStrategies(files, strategies)
        val needsSourceAccountIds =
            files
                .filter { file -> matches[file.id]?.needsSourceAccountOverride() == true && file.id !in directoryAccounts }
                .mapTo(mutableSetOf()) { it.id }
        val needsAttention =
            files.mapNotNull { file ->
                when {
                    matches[file.id] == null -> NeedsAttention(file.originalFileName, NeedsAttentionReason.NO_STRATEGY)
                    file.id in needsSourceAccountIds -> NeedsAttention(file.originalFileName, NeedsAttentionReason.NEEDS_SOURCE_ACCOUNT)
                    else -> null
                }
            }
        return CsvImportAllPlan(
            files = files,
            matches = matches,
            directoryAccounts = directoryAccounts,
            needsAttention = needsAttention,
            strategies = strategies,
            currencies = currencyRepository.getAllCurrencies().first(),
            passThroughAccounts = passThroughAccountRepository.getAll().first(),
            attributeAccountMatchers = AttributeAccountMatcher.registry(accountAttributeRepository.getAll().first()),
            needsSourceAccountIds = needsSourceAccountIds,
        )
    }

    /** Each of [files]' strategy as "import all" would match it (null = no match), without the rest of a plan. */
    suspend fun matchCsvStrategies(files: List<CsvImport>): Map<CsvImportId, CsvImportStrategy?> =
        matchCsvStrategies(files, csvImportStrategyRepository.getAllStrategies().first())

    private suspend fun matchCsvStrategies(
        files: List<CsvImport>,
        strategies: List<CsvImportStrategy>,
    ): Map<CsvImportId, CsvImportStrategy?> = files.associate { it.id to matchCsvStrategy(it, strategies, preferLastApplied = false) }

    /**
     * Applies [plan]. A file whose folder gives no account uses [sourceAccountOverride] when its strategy
     * needs one; files with no matching strategy are skipped and counted.
     */
    suspend fun applyCsvImportAll(
        plan: CsvImportAllPlan,
        sourceAccountOverride: AccountId?,
        onProgress: suspend (Progress) -> Unit,
        refreshViews: Boolean = true,
    ): BulkApplyResult =
        writeLock.withLock {
            bulkApplyCsv(
                imports = plan.files,
                sourceAccountOverride = sourceAccountOverride,
                strategies = plan.strategies,
                currencies = plan.currencies,
                accountMappingRepository = accountMappingRepository,
                accountRepository = accountRepository,
                csvImportRepository = csvImportRepository,
                maintenance = maintenance,
                importEngine = importEngine,
                onProgress = onProgress,
                passThroughAccounts = plan.passThroughAccounts,
                cryptoRepository = cryptoRepository,
                attributeAccountMatchers = plan.attributeAccountMatchers,
                directoryAccounts = plan.directoryAccounts,
                tradeRepository = tradeRepository,
                refreshViews = refreshViews,
            )
        }

    /** Plans a QIF "import all" over [imports] (every unimported file when null). */
    suspend fun planQifImportAll(imports: List<QifImport>? = null): QifImportAllPlan {
        val files = imports ?: unimportedQifFiles()
        val strategies = csvImportStrategyRepository.getAllStrategies().first()
        val qifStrategies = strategies.qifCompatible()
        val directoryAccounts = importDirectoryRepository.qifImportSourceAccounts()
        val needsAttention = mutableListOf<NeedsAttention>()
        val blockedIds = mutableSetOf<QifImportId>()
        for (file in files) {
            val rows = QifCsvAdapter.toRows(qifImportRepository.getImportRecords(file.id, STRATEGY_CONTENT_SAMPLE_SIZE, 0))
            // An empty file imports nothing either way, so it's nothing to resolve.
            if (rows.isEmpty()) continue
            val strategy = qifStrategies.selectForQifContent(rows, QifCsvAdapter.columns)
            val reason =
                when {
                    strategy == null -> NeedsAttentionReason.NO_STRATEGY
                    file.id !in directoryAccounts && strategy.config.fieldMappings[TransferField.SOURCE_ACCOUNT] == null ->
                        NeedsAttentionReason.NEEDS_SOURCE_ACCOUNT
                    else -> null
                }
            if (reason != null) {
                needsAttention += NeedsAttention(file.originalFileName, reason)
                blockedIds += file.id
            }
        }
        return QifImportAllPlan(
            files = files,
            directoryAccounts = directoryAccounts,
            defaultSourceAccountId = lastQifAccountId(),
            needsAttention = needsAttention,
            strategies = strategies,
            currencies = currencyRepository.getAllCurrencies().first(),
            blockedIds = blockedIds,
        )
    }

    /** Applies [plan] with [sourceAccountId] for files whose folder has no account, and remembers that account. */
    suspend fun applyQifImportAll(
        plan: QifImportAllPlan,
        sourceAccountId: AccountId?,
        onProgress: suspend (Progress) -> Unit,
        refreshViews: Boolean = true,
    ): BulkApplyResult =
        writeLock.withLock {
            bulkApplyQif(
                imports = plan.files,
                sourceAccountId = sourceAccountId,
                strategies = plan.strategies,
                currencies = plan.currencies,
                accountMappingRepository = accountMappingRepository,
                accountRepository = accountRepository,
                qifImportRepository = qifImportRepository,
                maintenance = maintenance,
                importEngine = importEngine,
                onProgress = onProgress,
                directoryAccounts = plan.directoryAccounts,
                refreshViews = refreshViews,
            ).also { sourceAccountId?.let { importEngine.setLastQifAccount(it) } }
        }

    /** Plans a CSV "re-import all" over [imports]: each file keeps the strategy it was last imported with. */
    suspend fun planCsvReimportAll(imports: List<CsvImport>): CsvReimportAllPlan {
        val strategies = csvImportStrategyRepository.getAllStrategies().first()
        return CsvReimportAllPlan(
            files = imports,
            matches = imports.associate { it.id to matchCsvStrategy(it, strategies, preferLastApplied = true) },
            strategies = strategies,
            currencies = currencyRepository.getAllCurrencies().first(),
            passThroughAccounts = passThroughAccountRepository.getAll().first(),
            attributeAccountMatchers = AttributeAccountMatcher.registry(accountAttributeRepository.getAll().first()),
        )
    }

    /** Applies [plan]; each file's source account comes from its own import history. */
    suspend fun applyCsvReimportAll(
        plan: CsvReimportAllPlan,
        onProgress: suspend (Progress) -> Unit,
    ): BulkReimportResult =
        bulkReimportCsv(
            imports = plan.files,
            sourceAccountOverride = null,
            strategies = plan.strategies,
            currencies = plan.currencies,
            accountMappingRepository = accountMappingRepository,
            accountRepository = accountRepository,
            csvImportRepository = csvImportRepository,
            transactionRepository = transactionRepository,
            relationshipRepository = transferRelationshipRepository,
            transferSourceRepository = transferSourceRepository,
            tradeRepository = tradeRepository,
            maintenance = maintenance,
            importEngine = importEngine,
            onProgress = onProgress,
            passThroughAccounts = plan.passThroughAccounts,
            cryptoRepository = cryptoRepository,
            attributeAccountMatchers = plan.attributeAccountMatchers,
        )

    /** Plans a QIF "re-import all" over [imports], preselecting the last QIF account and the default currency. */
    suspend fun planQifReimportAll(imports: List<QifImport>): QifReimportAllPlan {
        val currencies = currencyRepository.getAllCurrencies().first()
        val defaultCurrencyId = settingsRepository.getDefaultCurrencyId().first()
        return QifReimportAllPlan(
            files = imports,
            defaultSourceAccountId = lastQifAccountId(),
            defaultCurrencyId = defaultCurrencyId?.takeIf { id -> currencies.any { it.id == id } } ?: currencies.firstOrNull()?.id,
            strategies = csvImportStrategyRepository.getAllStrategies().first(),
            currencies = currencies,
        )
    }

    /** Applies [plan] in [currencyId] (QIF carries none); [sourceAccountId] is for not-yet-imported records. */
    suspend fun applyQifReimportAll(
        plan: QifReimportAllPlan,
        sourceAccountId: AccountId?,
        currencyId: CurrencyId,
        onProgress: suspend (Progress) -> Unit,
    ): BulkReimportResult =
        bulkReimportQif(
            imports = plan.files,
            sourceAccountOverride = sourceAccountId,
            currencyId = currencyId,
            strategies = plan.strategies,
            currencies = plan.currencies,
            accountMappingRepository = accountMappingRepository,
            accountRepository = accountRepository,
            qifImportRepository = qifImportRepository,
            transactionRepository = transactionRepository,
            transferSourceRepository = transferSourceRepository,
            maintenance = maintenance,
            importEngine = importEngine,
            onProgress = onProgress,
        )

    private suspend fun unimportedCsvFiles(): List<CsvImport> =
        csvImportRepository.getAllImports().first().filter { !it.ignored && it.lastAppliedAt == null }

    private suspend fun unimportedQifFiles(): List<QifImport> =
        qifImportRepository.getAllImports().first().filter { !it.ignored && it.lastAppliedAt == null }

    // getAllImports() doesn't populate columns, so the full import is re-read before matching.
    private suspend fun matchCsvStrategy(
        listed: CsvImport,
        strategies: List<CsvImportStrategy>,
        preferLastApplied: Boolean,
    ): CsvImportStrategy? {
        val full = csvImportRepository.getImport(listed.id).first() ?: return null
        if (preferLastApplied) {
            full.lastAppliedStrategyId?.let { id -> strategies.find { it.id == id } }?.let { return it }
        }
        val sampleRows = csvImportRepository.getImportRows(listed.id, limit = STRATEGY_CONTENT_SAMPLE_SIZE, offset = 0)
        return strategies.selectForCsv(full.originalFileName, full.columns, sampleRows)
    }

    // The last account a QIF import used, if it still exists.
    private suspend fun lastQifAccountId(): AccountId? {
        val last = settingsRepository.getLastQifAccountId().first() ?: return null
        return last.takeIf { id -> accountRepository.getAllAccounts().first().any { it.id == id } }
    }

    private fun requireFileSourceFactory(): ImportFileSourceFactory =
        checkNotNull(fileSourceFactory) { "Import directories can't be read on this platform" }

    // Downloads [directory]'s own importable files, reporting done / total on [task].
    private suspend fun downloadFolder(
        task: TaskScope,
        directory: ImportDirectory,
    ): ScanResult =
        scanImportDirectory(
            directory = directory,
            fileSource = requireFileSourceFactory().create(directory),
            importDirectoryRepository = importDirectoryRepository,
            csvImportRepository = csvImportRepository,
            qifImportRepository = qifImportRepository,
            importEngine = importEngine,
            dbLock = writeLock,
            onProgress = { done, total -> task.report(Progress.counted("$done / $total files", done, total)) },
        )

    // Walks [root]'s folder tree all the way down and creates a (non-top-level) import directory for every
    // NEW subfolder that contains importable files, calling [onCreated] as each one is created so the caller
    // can download it while the walk goes on. Returns the count created; [dirsByRef] gains the new ones.
    private suspend fun createSubdirectories(
        root: ImportDirectory,
        dirsByRef: MutableMap<String, ImportDirectory>,
        onCreated: (ImportDirectory) -> Unit = {},
    ): Int {
        val factory = requireFileSourceFactory()
        var created = 0
        discoverImportableFolders(
            rootFolderRef = root.folderRef,
            rootDisplayPath = root.displayPath ?: root.folderRef,
            openFolder = { ref -> factory.create(probeDirectory(root, ref)) },
            onFound = { folder ->
                writeLock.withLock {
                    if (folder.folderRef !in dirsByRef) {
                        val leaf =
                            ImportDirectory(
                                id = ImportDirectoryId(Uuid.random()),
                                name = folder.displayPath,
                                provider = root.provider,
                                folderRef = folder.folderRef,
                                displayPath = folder.displayPath,
                                providerConfig = root.providerConfig,
                                deviceId = root.deviceId,
                                topLevel = false,
                                parentId = root.id,
                                createdAt = Clock.System.now(),
                                updatedAt = Clock.System.now(),
                            )
                        importEngine.createImportDirectory(leaf)
                        dirsByRef[folder.folderRef] = leaf
                        created++
                        onCreated(leaf)
                    }
                }
            },
        )
        return created
    }
}

// A throwaway directory used only to open an arbitrary folder [folderRef] via the file-source factory
// (which reads provider/folderRef/providerConfig); other fields are inherited from [root].
private fun probeDirectory(
    root: ImportDirectory,
    folderRef: String,
): ImportDirectory = root.copy(id = ImportDirectoryId(Uuid.random()), folderRef = folderRef)
