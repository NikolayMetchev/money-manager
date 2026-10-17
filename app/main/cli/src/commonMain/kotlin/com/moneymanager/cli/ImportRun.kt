package com.moneymanager.cli

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.mordant.rendering.Widget
import com.github.ajalt.mordant.table.table
import com.moneymanager.domain.model.importdirectory.ImportDirectory
import com.moneymanager.session.ApiImport
import com.moneymanager.session.DatabaseSession
import com.moneymanager.session.DirectoryImport
import com.moneymanager.session.ImportRequest
import com.moneymanager.session.ImportRunOutcome
import com.moneymanager.session.SessionImports
import com.moneymanager.tasks.TaskManager
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first

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
        val imports =
            SessionImports(services, runtime.services.credentialVault, runtime.services.importFileSources, runtime.services.httpEngine)
        // Names resolve before anything downloads or prompts, so a typo is a usage error up front.
        val folders =
            directories?.let {
                resolveDirectories(
                    services.imports.importDirectoryRepository
                        .getAllDirectories()
                        .first(),
                    it.names,
                )
            }
        val strategyIds =
            apis?.let { request ->
                val strategies =
                    services.imports.apiImportStrategyRepository
                        .getAllStrategies()
                        .first()
                resolveByName(strategies, request.strategies, "API strategy") { it.name }?.mapTo(mutableSetOf()) { it.id }
            }
        val request =
            ImportRequest(
                directories = directories?.let { DirectoryImport(folders, it.apply, it.redownload) },
                apis = apis?.let { ApiImport(strategyIds, it.forceFull, it.apply, it.optional) },
            )
        val outcome =
            coroutineScope {
                val manager = TaskManager(this)
                runtime.withProgress(manager) { imports.run(manager, request) }
            }
        return outcome.toJson()
    }
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

private fun ImportRunOutcome.toJson() =
    ImportJson(
        directories = directories?.toJson(),
        apis = apis?.toJson(),
        needsAttention = needsAttention.map { NeedsAttentionJson(it.kind, it.subject, it.reason) },
        errors = errors,
    )

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
