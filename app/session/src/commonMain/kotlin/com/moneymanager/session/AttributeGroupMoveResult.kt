package com.moneymanager.session

import com.moneymanager.apiimporter.ApiBulkReimportResult
import com.moneymanager.csvimporter.BulkReimportResult
import com.moneymanager.domain.model.AccountId
import com.moneymanager.importengineapi.AccountAttributeEdit
import com.moneymanager.importengineapi.setAccountAttributeValues
import com.moneymanager.tasks.TaskScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** What moving an attribute group did: [files] and [apiSessions] are null when nothing referenced the old account. */
data class AttributeGroupMoveResult(
    val attributesMoved: Int,
    val files: BulkReimportResult?,
    val apiSessions: ApiBulkReimportResult?,
) {
    fun summary(): String =
        buildString {
            append("Moved $attributesMoved attribute${if (attributesMoved == 1) "" else "s"}")
            files?.let { append(". ${it.toSummary()}") }
            apiSessions?.let {
                append(". Re-imported ${it.sessionsReimported} API session${if (it.sessionsReimported == 1) "" else "s"}")
                if (it.failures.isNotEmpty()) append(" · ${it.failures.size} failed")
            }
        }
}

/**
 * Moves the attribute group [groupKey] (a sort code and account number, say) from [from] to [to], then re-imports
 * every file and API session whose records name [from], so rows that identify an account by those attributes land
 * on [to]. The re-import is planned first, so a file that can't be imported again refuses the move before any
 * attribute changes. The re-run keeps transaction ids and hand edits (see **Re-import keeps transaction ids**).
 */
suspend fun SessionImports.moveAttributeGroup(
    task: TaskScope,
    from: AccountId,
    groupKey: String,
    to: AccountId,
): AttributeGroupMoveResult {
    require(from != to) { "The group is already on that account" }
    require(groupKey.isNotEmpty()) { "Only a group of attributes can be moved" }
    val members =
        services.accounts.accountAttributeRepository
            .getByAccount(from)
            .first()
            .filter { it.groupKey == groupKey }
    require(members.isNotEmpty()) { "The account has no such attribute group" }

    task.report("Finding what references the account")
    val sources = services.transactions.observationRepository.getRecordSourcesOnAccount(from)
    val files =
        sources.csvImports.mapNotNull {
            services.imports.csvImportRepository
                .getImport(it)
                .first()
        }
    // Planned before anything is saved: a refusal leaves the attributes where they were.
    val reimportFiles = if (files.isEmpty()) null else directories.reimports.prepareAll(files, onProgress = { task.report(it) })

    task.report("Moving the attributes")
    services.transactions.importEngine.setAccountAttributeValues(
        members.map { AccountAttributeEdit(from, it.attributeType.id, "", it.id, it.groupKey) } +
            members.map { AccountAttributeEdit(to, it.attributeType.id, it.value, null, it.groupKey) },
    )

    try {
        val fileResult = reimportFiles?.invoke()
        if (fileResult != null && fileResult.filesFailed > 0) {
            task.reportIssue("${fileResult.filesFailed} file(s) failed to re-import; retry them from their import screen")
        }
        val sessionResult =
            sources.apiSessions.takeIf { it.isNotEmpty() }?.let { sessions ->
                task.report("Re-importing ${sessions.size} API session${if (sessions.size == 1) "" else "s"}")
                apis.reimportSessions(sessions, directories.reimports.reads, onProgress = { task.report(it) })
            }
        sessionResult?.failures?.forEach { task.reportIssue("API session ${it.sessionId}: ${it.message}") }
        return AttributeGroupMoveResult(members.size, fileResult, sessionResult)
    } finally {
        withContext(NonCancellable) { services.imports.maintenance.refreshMaterializedViews() }
    }
}
