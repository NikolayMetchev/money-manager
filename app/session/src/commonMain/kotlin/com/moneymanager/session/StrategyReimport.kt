package com.moneymanager.session

import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.strategy.StrategyKey
import com.moneymanager.domain.strategy.StrategyKind
import com.moneymanager.domain.strategy.isFileStrategy
import com.moneymanager.tasks.TaskScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** The previously applied files and sessions covered by one update offer. */
data class StrategyReimport(
    val files: List<CsvImport>,
    val sessions: Set<ApiSessionId>,
) {
    val isEmpty: Boolean get() = files.isEmpty() && sessions.isEmpty()
}

suspend fun SessionImports.findStrategyReimports(
    csvNames: Set<String>,
    apiNames: Set<String>,
): StrategyReimport {
    val imports = services.imports
    val csvIds =
        csvNames
            .mapNotNull {
                imports.csvImportStrategyRepository
                    .getStrategyByName(it)
                    .first()
                    ?.id
            }.toSet()
    val apiIds =
        apiNames
            .mapNotNull {
                imports.apiImportStrategyRepository
                    .getStrategyByName(it)
                    .first()
                    ?.id
            }.toSet()
    val files =
        if (csvIds.isEmpty()) {
            emptyList()
        } else {
            imports.csvImportRepository.getAllImports().first().filter {
                it.lastAppliedAt != null && !it.ignored && it.lastAppliedStrategyId in csvIds
            }
        }
    val sessions =
        if (apiIds.isEmpty()) {
            emptySet()
        } else {
            val api = imports.apiSessionRepository
            val applied = api.getImportedSessionRevisions().mapTo(mutableSetOf()) { it.sessionId }
            api
                .getAllCredentials()
                .filter { it.strategyId in apiIds }
                .flatMap { api.getSessionsByCredential(it.id) }
                .mapNotNullTo(mutableSetOf()) { it.id.takeIf(applied::contains) }
        }
    return StrategyReimport(files, sessions)
}

suspend fun SessionImports.findStrategyReimports(keys: Set<StrategyKey>): StrategyReimport =
    findStrategyReimports(
        csvNames = keys.filter { it.kind.isFileStrategy }.mapTo(mutableSetOf()) { it.name },
        apiNames = keys.filter { it.kind == StrategyKind.API }.mapTo(mutableSetOf()) { it.name },
    )

suspend fun SessionImports.reimportUpdatedStrategies(
    offer: StrategyReimport,
    task: TaskScope,
): String {
    val summaries = mutableListOf<String>()
    try {
        if (offer.files.isNotEmpty()) {
            try {
                val result = directories.reimports.prepareAll(offer.files, onProgress = { task.report(it) })()
                summaries += result.toSummary()
                if (result.filesFailed > 0) task.reportIssue("${result.filesFailed} file(s) failed to re-import")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (expected: Exception) {
                if (offer.sessions.isEmpty()) throw expected
                task.reportIssue("Could not re-import the files: ${expected.message}")
            }
        }
        if (offer.sessions.isNotEmpty()) {
            val result = apis.reimportSessions(offer.sessions, directories.reimports.reads, onProgress = { task.report(it) })
            summaries += "Re-imported ${result.sessionsReimported} API session(s)"
            result.failures.forEach { task.reportIssue("API session ${it.sessionId}: ${it.message}") }
        }
        return summaries.joinToString(". ")
    } finally {
        withContext(NonCancellable) { services.imports.maintenance.refreshMaterializedViews() }
    }
}
