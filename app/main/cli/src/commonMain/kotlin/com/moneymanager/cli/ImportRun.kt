package com.moneymanager.cli

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.mordant.rendering.Widget
import com.github.ajalt.mordant.table.table
import com.moneymanager.apiimport.API_DOWNLOAD_IMPORT_ALL_TASK_KEY
import com.moneymanager.apiimport.ApiDownloadAndImportAllResult
import com.moneymanager.apiimport.ApiImportService
import com.moneymanager.credentialvault.CredentialVaultLockedException
import com.moneymanager.credentialvault.VaultState
import com.moneymanager.domain.model.ApiCredential
import com.moneymanager.domain.model.importdirectory.ImportDirectory
import com.moneymanager.importdirectory.IMPORT_ALL_TASK_KEY
import com.moneymanager.importdirectory.ImportAllResult
import com.moneymanager.importdirectory.ImportDirectoryService
import com.moneymanager.session.DatabaseSession
import com.moneymanager.session.apiImportService
import com.moneymanager.session.importDirectoryService
import com.moneymanager.tasks.TaskManager
import com.moneymanager.tasks.TaskScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex

/** What to do with import directories: [names] limits it to those folders (all when empty). */
internal data class DirectoriesRequest(
    val names: List<String>,
    val apply: Boolean,
    val redownload: Boolean,
)

/**
 * What to do with API connections: [strategies] limits it to those strategies' connections (all when
 * empty). [optional] (`import all`) skips APIs when none are installed, and lists a locked vault under
 * needsAttention instead of failing the run.
 */
internal data class ApisRequest(
    val strategies: List<String>,
    val forceFull: Boolean,
    val apply: Boolean,
    val optional: Boolean = false,
)

/**
 * One `mm import` run: resolves the names first (a typo is a usage error before anything downloads),
 * then runs the directory and API imports as tasks (at once, under one parent, when both are asked for)
 * with live progress, refreshes the materialized views once, and prints the summary. Anything failed or
 * left for a person exits with [ExitCodes.NEEDS_ATTENTION] after the summary.
 */
internal class ImportRun(
    private val runtime: CliRuntime,
) {
    suspend fun run(
        directories: DirectoriesRequest?,
        apis: ApisRequest?,
    ) {
        val summary = runtime.withSession { session -> runOn(session, directories, apis) }
        runtime.output.emit(summary, ImportJson.serializer(), table = ::summaryTable)
        if (summary.incomplete) {
            throw NeedsAttention(
                if (summary.needsAttention.isEmpty()) {
                    "Some imports failed; see above."
                } else {
                    "${summary.needsAttention.size} item(s) need attention; see above."
                },
            )
        }
    }

    private suspend fun runOn(
        session: DatabaseSession,
        directories: DirectoriesRequest?,
        apis: ApisRequest?,
    ): ImportJson {
        val services = session.services
        // One lock for both: the engine doesn't serialize writes, and `import all` runs the two at once.
        val writeLock = Mutex()
        val directoryService = services.importDirectoryService(runtime.services.importFileSources, writeLock)
        val apiService = services.apiImportService(runtime.services.credentialVault, writeLock, runtime.services.httpEngine)

        val allDirectories =
            services.imports.importDirectoryRepository
                .getAllDirectories()
                .first()
        val folders = directories?.let { resolveDirectories(allDirectories, it.names) }
        val needsAttention = mutableListOf<NeedsAttentionJson>()
        val targets =
            apis?.let { request ->
                try {
                    apiTargets(apiService, session, request)
                } catch (locked: CredentialVaultLockedException) {
                    if (!request.optional) throw locked
                    needsAttention += NeedsAttentionJson("vaultLocked", "APIs", "the credential vault is locked: ${locked.message}")
                    null
                }
            }

        val outcome =
            coroutineScope {
                val manager = TaskManager(this)
                runtime.withProgress(manager) {
                    val apiRun = targets?.let { list -> list to apis }
                    when {
                        directories != null && apiRun != null ->
                            manager.await("import-all", "Import all", ImportOutcome::summary) {
                                importBoth(directoryService, folders, directories, apiService, apiRun.first, apiRun.second)
                            }
                        directories != null ->
                            manager.await(IMPORT_ALL_TASK_KEY, "Import directories", ImportOutcome::summary) {
                                ImportOutcome(directories = importDirectories(directoryService, folders, directories))
                            }
                        apiRun != null ->
                            manager.await(API_DOWNLOAD_IMPORT_ALL_TASK_KEY, "Import APIs", ImportOutcome::summary) {
                                ImportOutcome(apis = importApis(apiService, apiRun.first, apiRun.second))
                            }
                        else -> ImportOutcome()
                    }
                }
            }
        // Once for the whole run: every import above was told not to.
        if (directories?.apply == true || apis?.apply == true) services.imports.maintenance.refreshMaterializedViews()
        return outcome.toJson(needsAttention)
    }

    // The APIs a run downloads: unlocks the vault (prompting if it must), gives every installed strategy with
    // secrets its connection row, then picks the connections of [request]'s strategies. Null: nothing to do.
    private suspend fun apiTargets(
        apiService: ApiImportService,
        session: DatabaseSession,
        request: ApisRequest,
    ): List<ApiCredential>? {
        val imports = session.services.imports
        val strategies = imports.apiImportStrategyRepository.getAllStrategies().first()
        val vault = runtime.services.credentialVault
        // `import all` on a database nobody connected an API in: there are no secrets to unlock.
        val nothingConnected =
            strategies.isEmpty() ||
                (imports.apiSessionRepository.getAllCredentials().isEmpty() && vault.state.value is VaultState.NoFile)
        if (request.optional && nothingConnected) return null
        val selected = resolveByName(strategies, request.strategies, "API strategy") { it.name }
        vault.requireUnlocked("Import from APIs")
        apiService.ensureConnections()
        val selectedIds = selected?.mapTo(mutableSetOf()) { it.id }
        val credentials = imports.apiSessionRepository.getAllCredentials()
        return credentials.filter { selectedIds == null || it.strategyId in selectedIds }
    }

    private suspend fun TaskScope.importBoth(
        directoryService: ImportDirectoryService,
        folders: List<ImportDirectory>?,
        directories: DirectoriesRequest,
        apiService: ApiImportService,
        targets: List<ApiCredential>,
        apis: ApisRequest,
    ): ImportOutcome =
        coroutineScope {
            // Each side fails on its own: a broken Drive sign-in mustn't stop the API imports, or the reverse.
            val dirs =
                async {
                    isolated("Import directories") {
                        child(IMPORT_ALL_TASK_KEY, "Import directories", "Listing folders…") {
                            importDirectories(directoryService, folders, directories)
                        }
                    }
                }
            val api =
                async {
                    isolated("Import APIs") {
                        child(API_DOWNLOAD_IMPORT_ALL_TASK_KEY, "Import APIs", "Connecting…") { importApis(apiService, targets, apis) }
                    }
                }
            val (dirResult, dirError) = dirs.await()
            val (apiResult, apiError) = api.await()
            ImportOutcome(dirResult, apiResult, listOfNotNull(dirError, apiError))
        }

    private suspend fun TaskScope.importDirectories(
        service: ImportDirectoryService,
        folders: List<ImportDirectory>?,
        request: DirectoriesRequest,
    ) = service.importAll(this, folders, apply = request.apply, redownloadFirst = request.redownload, refreshViews = false)

    private suspend fun TaskScope.importApis(
        service: ApiImportService,
        targets: List<ApiCredential>,
        request: ApisRequest,
    ) = service.downloadAndImportAll(
        task = this,
        targets = targets,
        forceFullIds = if (request.forceFull) targets.mapTo(mutableSetOf()) { it.id } else emptySet(),
        importSessions = request.apply,
        refreshViews = false,
    )
}

/** The folders [names] pick, each with its subfolders (null: every folder). */
internal fun resolveDirectories(
    all: List<ImportDirectory>,
    names: List<String>,
): List<ImportDirectory>? = resolveByName(all, names, "import directory") { it.name }

/**
 * The items [names] pick from [all] by [name], ignoring case (null when [names] is empty: no filter). A name
 * matching none, or several, is a [UsageError] listing the candidates.
 */
internal fun <T> resolveByName(
    all: List<T>,
    names: List<String>,
    what: String,
    name: (T) -> String,
): List<T>? {
    if (names.isEmpty()) return null
    return names.distinct().map { wanted ->
        val matches = all.filter { name(it).equals(wanted, ignoreCase = true) }
        when (matches.size) {
            1 -> matches.single()
            0 -> throw UsageError("No $what is named '$wanted'. Known: ${all.map(name).sorted().joinToString().ifEmpty { "none" }}")
            else -> throw UsageError("${matches.size} of the ${what}s are named '$wanted'; rename one so it can be picked")
        }
    }
}

// Runs [block] as this manager's top-level task and returns what it returned, or rethrows what it threw:
// the manager records a task's outcome but doesn't hand it back. Waits for the task to settle so the
// progress display ends on its final state.
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

private data class ImportOutcome(
    val directories: ImportAllResult? = null,
    val apis: ApiDownloadAndImportAllResult? = null,
    val errors: List<String> = emptyList(),
) {
    fun summary(): String = listOfNotNull(directories?.summary(), apis?.summary(), *errors.toTypedArray()).joinToString(" ")

    fun toJson(extraAttention: List<NeedsAttentionJson>) =
        ImportJson(
            directories = directories?.toJson(),
            apis = apis?.toJson(),
            needsAttention =
                directories?.needsAttention.orEmpty().map { it.toJson() } +
                    apis?.needsAttention.orEmpty().map { it.toJson() } +
                    extraAttention,
            errors = errors,
        )
}

private fun summaryTable(summary: ImportJson): Widget =
    table {
        header { row("Kind", "Name", "Status", "Detail") }
        body {
            summary.directories?.let { dirs ->
                dirs.folders.forEach { row("folder", it.name, if (it.failed > 0) "failed" else "downloaded", folderDetail(it)) }
                dirs.failures.forEach { row("folder", "", "failed", it) }
                dirs.redownload?.let { re -> row("redownload", "", "", "${re.replaced.size} replaced, ${re.unchanged.size} unchanged") }
                dirs.files.forEach { row(it.format, it.file, it.status, it.error ?: "${it.transfers} new, ${it.duplicates} duplicates") }
            }
            summary.apis?.let { apis ->
                apis.connections.forEach { row("connection", it.strategy.orEmpty(), it.status, it.message.orEmpty()) }
                apis.sessions.forEach {
                    row("session", "${it.strategy.orEmpty()} #${it.id}", it.status, it.error ?: "${it.transactions} transactions")
                }
            }
            summary.needsAttention.forEach { row("attention", it.subject, it.kind, it.reason) }
            summary.errors.forEach { row("error", "", "failed", it) }
        }
    }

private fun folderDetail(folder: FolderDownloadJson): String =
    buildString {
        append("${folder.downloaded} new, ${folder.unchanged} unchanged")
        if (folder.skipped > 0) append(", ${folder.skipped} skipped")
        if (folder.failed > 0) append(", ${folder.failed} failed: ${folder.failures.joinToString()}")
    }
