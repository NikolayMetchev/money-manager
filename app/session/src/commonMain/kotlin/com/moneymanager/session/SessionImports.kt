package com.moneymanager.session

import com.moneymanager.apiimport.API_DOWNLOAD_IMPORT_ALL_TASK_KEY
import com.moneymanager.apiimport.ApiDownloadAndImportAllResult
import com.moneymanager.apiimport.ApiImportService
import com.moneymanager.apiimport.ApiSessionNeedsAttention
import com.moneymanager.credentialvault.CredentialVault
import com.moneymanager.credentialvault.CredentialVaultLockedException
import com.moneymanager.credentialvault.VaultState
import com.moneymanager.database.di.AppServices
import com.moneymanager.domain.model.ApiCredential
import com.moneymanager.domain.model.ApiImportStrategyId
import com.moneymanager.domain.model.importdirectory.ImportDirectory
import com.moneymanager.importdirectory.IMPORT_ALL_TASK_KEY
import com.moneymanager.importdirectory.ImportAllResult
import com.moneymanager.importdirectory.ImportDirectoryService
import com.moneymanager.importdirectory.NeedsAttention
import com.moneymanager.importdirectory.NeedsAttentionReason
import com.moneymanager.importfilesource.ImportFileSourceFactory
import com.moneymanager.tasks.TaskManager
import com.moneymanager.tasks.TaskScope
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** Download, and with [apply] import, the files of [folders] (null: every folder), re-downloading them first when [redownload]. */
data class DirectoryImport(
    val folders: List<ImportDirectory>? = null,
    val apply: Boolean = true,
    val redownload: Boolean = false,
)

/**
 * Download, and with [apply] import, the API connections of [strategyIds] (null: every strategy). [optional]
 * (import all) skips APIs when none are connected, and reports a locked vault as needing attention instead of
 * failing the run.
 */
data class ApiImport(
    val strategyIds: Set<ApiImportStrategyId>? = null,
    val forceFull: Boolean = false,
    val apply: Boolean = true,
    val optional: Boolean = false,
)

/** What one run imports: directories, APIs, or both at once. */
data class ImportRequest(
    val directories: DirectoryImport? = null,
    val apis: ApiImport? = null,
)

/** Something only a person can resolve: [kind] says what, [subject] names the file, card or session. */
data class ImportAttention(
    val kind: String,
    val subject: String,
    val reason: String,
)

/**
 * What an import run did. A section is null when the run didn't do it; [errors] holds a section that failed as
 * a whole (a combined run keeps going past one), and [vaultLocked] why an optional API section was skipped.
 */
data class ImportRunOutcome(
    val directories: ImportAllResult? = null,
    val apis: ApiDownloadAndImportAllResult? = null,
    val errors: List<String> = emptyList(),
    val vaultLocked: String? = null,
) {
    val needsAttention: List<ImportAttention>
        get() =
            directories?.needsAttention.orEmpty().map { it.toAttention() } +
                apis?.needsAttention.orEmpty().map { it.toAttention() } +
                listOfNotNull(vaultLocked?.let { ImportAttention("vaultLocked", "APIs", "the credential vault is locked: $it") })

    fun summary(): String = listOfNotNull(directories?.summary(), apis?.summary(), *errors.toTypedArray()).joinToString(" ")
}

/**
 * A database session's imports: the [ImportDirectoryService] and [ApiImportService] sharing one write lock (the
 * engine doesn't serialize writes, and a combined run does both at once), and [run], the one way a front end
 * (`mm import …`, the TUI's Imports tab) imports directories, APIs or both. Build one per session.
 */
class SessionImports(
    private val services: AppServices,
    private val vault: CredentialVault,
    fileSources: ImportFileSourceFactory?,
    httpEngine: HttpClientEngine? = null,
) {
    private val writeLock = Mutex()
    val directories: ImportDirectoryService = services.importDirectoryService(fileSources, writeLock)
    val apis: ApiImportService = services.apiImportService(vault, writeLock, httpEngine)

    private val mutableRunning = MutableStateFlow(false)
    private val mutableLastRun = MutableStateFlow<ImportRunOutcome?>(null)

    /** Whether a [run] is under way, from its vault prompt to its last view refresh. */
    val running: StateFlow<Boolean> = mutableRunning.asStateFlow()

    /** The latest run's outcome, which outlives whichever screen started it. */
    val lastRun: StateFlow<ImportRunOutcome?> = mutableLastRun.asStateFlow()

    /**
     * Runs [request] as a top-level task of [manager] and returns what it did. The vault is unlocked (prompting if
     * it must) before any task starts, so declining leaves no failed task behind. The materialized views refresh
     * once at the end, also when a section failed or the run was cancelled, so whatever it wrote shows up.
     * One run at a time: starting another while one is under way throws [ImportAlreadyRunningException].
     */
    suspend fun run(
        manager: TaskManager,
        request: ImportRequest,
    ): ImportRunOutcome {
        if (!mutableRunning.compareAndSet(expect = false, update = true)) throw ImportAlreadyRunningException()
        try {
            return runExclusively(manager, request)
        } finally {
            mutableRunning.value = false
        }
    }

    private suspend fun runExclusively(
        manager: TaskManager,
        request: ImportRequest,
    ): ImportRunOutcome {
        var vaultLocked: String? = null
        val targets =
            request.apis?.let { api ->
                try {
                    apiTargets(api)
                } catch (locked: CredentialVaultLockedException) {
                    if (!api.optional) throw locked
                    vaultLocked = locked.message.orEmpty()
                    null
                }
            }
        val directories = request.directories
        val apis = targets?.let { it to checkNotNull(request.apis) }
        val outcome =
            try {
                when {
                    directories != null && apis != null -> {
                        manager.await("import-all", "Import all", ImportRunOutcome::summary) {
                            importBoth(directories, apis.first, apis.second)
                        }
                    }

                    directories != null -> {
                        manager.await(IMPORT_ALL_TASK_KEY, "Import directories", ImportRunOutcome::summary) {
                            ImportRunOutcome(directories = importDirectories(directories))
                        }
                    }

                    apis != null -> {
                        manager.await(API_DOWNLOAD_IMPORT_ALL_TASK_KEY, "Import APIs", ImportRunOutcome::summary) {
                            ImportRunOutcome(apis = importApis(apis.first, apis.second))
                        }
                    }

                    else -> {
                        ImportRunOutcome()
                    }
                }
            } finally {
                if (directories?.apply == true || request.apis?.apply == true) {
                    withContext(NonCancellable) { services.imports.maintenance.refreshMaterializedViews() }
                }
            }.copy(vaultLocked = vaultLocked)
        mutableLastRun.value = outcome
        return outcome
    }

    // The connections a run downloads: unlocks the vault, gives every installed strategy with secrets its
    // connection row, then picks [request]'s strategies' connections. Null: nothing to do.
    private suspend fun apiTargets(request: ApiImport): List<ApiCredential>? {
        val imports = services.imports
        val strategies = imports.apiImportStrategyRepository.getAllStrategies().first()
        // Import all on a database nobody connected an API in: there are no secrets to unlock.
        val nothingConnected =
            strategies.isEmpty() ||
                (imports.apiSessionRepository.getAllCredentials().isEmpty() && vault.state.value is VaultState.NoFile)
        if (request.optional && nothingConnected) return null
        vault.requireUnlocked("Import from APIs")
        apis.ensureConnections()
        val selected = request.strategyIds
        return imports.apiSessionRepository.getAllCredentials().filter { selected == null || it.strategyId in selected }
    }

    private suspend fun TaskScope.importBoth(
        directories: DirectoryImport,
        targets: List<ApiCredential>,
        request: ApiImport,
    ): ImportRunOutcome =
        coroutineScope {
            // Each side fails on its own: a broken Drive sign-in mustn't stop the API imports, or the reverse.
            val dirs =
                async {
                    isolated("Import directories") {
                        child(IMPORT_ALL_TASK_KEY, "Import directories", "Listing folders…") { importDirectories(directories) }
                    }
                }
            val api =
                async {
                    isolated("Import APIs") {
                        child(API_DOWNLOAD_IMPORT_ALL_TASK_KEY, "Import APIs", "Connecting…") { importApis(targets, request) }
                    }
                }
            val (dirResult, dirError) = dirs.await()
            val (apiResult, apiError) = api.await()
            ImportRunOutcome(dirResult, apiResult, listOfNotNull(dirError, apiError))
        }

    private suspend fun TaskScope.importDirectories(request: DirectoryImport) =
        directories.importAll(this, request.folders, apply = request.apply, redownloadFirst = request.redownload, refreshViews = false)

    private suspend fun TaskScope.importApis(
        targets: List<ApiCredential>,
        request: ApiImport,
    ) = apis.downloadAndImportAll(
        task = this,
        targets = targets,
        forceFullIds = if (request.forceFull) targets.mapTo(mutableSetOf()) { it.id } else emptySet(),
        importSessions = request.apply,
        refreshViews = false,
    )
}

/** Thrown by [SessionImports.run] while another run is under way. */
class ImportAlreadyRunningException : IllegalStateException("An import is already running")

/** The attention item's kind, as `mm import`'s JSON names it. */
fun NeedsAttention.toAttention(): ImportAttention =
    ImportAttention(
        kind =
            when (reason) {
                NeedsAttentionReason.NO_STRATEGY -> "noStrategy"
                NeedsAttentionReason.NEEDS_SOURCE_ACCOUNT -> "needsSourceAccount"
                NeedsAttentionReason.REDOWNLOAD_BLOCKED -> "redownloadBlocked"
                NeedsAttentionReason.REIMPORT_SUGGESTED -> "reimportSuggested"
                NeedsAttentionReason.UNASSIGNED_CARD -> "unassignedCard"
            },
        subject = subject,
        reason = reason.description,
    )

fun ApiSessionNeedsAttention.toAttention(): ImportAttention =
    ImportAttention(
        kind = "counterparties",
        subject = "${strategy.name} session #${session.id}",
        reason =
            "${counterparties.size} counterparty account name(s) to confirm: " +
                counterparties.joinToString { it.suggestedAccountName },
    )

// Runs [block] as this manager's top-level task and returns what it returned, or rethrows what it threw:
// the manager records a task's outcome but doesn't hand it back. Waits for the task to settle so a progress
// display ends on its final state.
private suspend fun <T> TaskManager.await(
    key: String,
    title: String,
    detail: (T) -> String,
    block: suspend TaskScope.() -> T,
): T {
    val result = CompletableDeferred<T>()
    val id =
        start(key, title, "Starting…") {
            try {
                block().also { result.complete(it) }.let(detail)
            } catch (expected: Exception) {
                // Cancellation included, so the caller never waits on a task that has stopped.
                result.completeExceptionally(expected)
                throw expected
            }
        }
    checkNotNull(id) { "$title is already running" }
    val value = result.await()
    tasks.first { all -> all.none { it.id == id && it.isRunning } }
    return value
}

// [block]'s result, or the message it failed with.
private suspend fun <T> isolated(
    what: String,
    block: suspend () -> T,
): Pair<T?, String?> =
    try {
        block() to null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (expected: Exception) {
        null to "$what failed: ${expected.message ?: expected::class.simpleName}"
    }
