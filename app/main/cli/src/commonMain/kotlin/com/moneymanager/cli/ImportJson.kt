package com.moneymanager.cli

import com.moneymanager.apiimport.ApiCredentialDownload
import com.moneymanager.apiimport.ApiDownloadAndImportAllResult
import com.moneymanager.apiimport.ApiDownloadOutcome
import com.moneymanager.apiimport.ApiSessionImport
import com.moneymanager.apiimport.ApiSessionImportStatus
import com.moneymanager.csvimporter.BulkFileOutcome
import com.moneymanager.csvimporter.BulkFileStatus
import com.moneymanager.csvimporter.DirectoryRedownloadResult
import com.moneymanager.domain.model.ImportFileKind
import com.moneymanager.domain.model.importFileKind
import com.moneymanager.importdirectory.ImportAllResult
import kotlinx.serialization.Serializable

/**
 * What `mm import dirs|apis|all` did (see `docs/cli-json.md`). A section is null when the command didn't
 * run it; [errors] holds a section that failed as a whole (only `import all` keeps going past one).
 */
@Serializable
internal data class ImportJson(
    val directories: DirectoriesImportJson? = null,
    val apis: ApisImportJson? = null,
    val needsAttention: List<NeedsAttentionJson>,
    val errors: List<String>,
) {
    /** Whether anything failed or was left for a person, which exits with [ExitCodes.NEEDS_ATTENTION]. */
    val incomplete: Boolean
        get() =
            needsAttention.isNotEmpty() || errors.isNotEmpty() || directories?.incomplete == true || apis?.incomplete == true
}

@Serializable
internal data class DirectoriesImportJson(
    val folders: List<FolderDownloadJson>,
    val foldersCreated: Int,
    val redownload: RedownloadJson? = null,
    val files: List<FileImportJson>,
    /** Folders that couldn't be downloaded or searched at all. */
    val failures: List<String>,
) {
    val incomplete: Boolean
        get() =
            failures.isNotEmpty() ||
                folders.any { it.failed > 0 } ||
                redownload?.failures?.isNotEmpty() == true ||
                files.any { it.status == FileImportJson.FAILED }
}

@Serializable
internal data class FolderDownloadJson(
    val id: String,
    val name: String,
    val downloaded: Int,
    val unchanged: Int,
    val skipped: Int,
    val failed: Int,
    val failures: List<String>,
)

/** File names, per what a re-download did with each. */
@Serializable
internal data class RedownloadJson(
    val replaced: List<String>,
    val unchanged: List<String>,
    val blocked: List<String>,
    val reimportSuggested: List<String>,
    val failures: List<String>,
)

@Serializable
internal data class FileImportJson(
    val id: String,
    val file: String,
    val format: String,
    val status: String,
    val strategy: String? = null,
    val transfers: Int,
    val duplicates: Int,
    val error: String? = null,
) {
    companion object {
        const val FAILED = "failed"
    }
}

@Serializable
internal data class ApisImportJson(
    val connections: List<ConnectionDownloadJson>,
    val sessions: List<SessionImportJson>,
) {
    val incomplete: Boolean
        get() =
            connections.any { it.status != ConnectionDownloadJson.DOWNLOADED } ||
                sessions.any { it.status == SessionImportJson.FAILED || it.status == SessionImportJson.SKIPPED }
}

@Serializable
internal data class ConnectionDownloadJson(
    val id: Long,
    val strategy: String? = null,
    /** `downloaded`, `notDownloaded` (no strategy, secrets or region), `failed`, or `busy` (already running). */
    val status: String,
    val sessionId: Long? = null,
    val message: String? = null,
) {
    companion object {
        const val DOWNLOADED = "downloaded"
    }
}

@Serializable
internal data class SessionImportJson(
    val id: Long,
    val connectionId: Long? = null,
    val strategy: String? = null,
    /** `imported`, `needsAttention`, `skipped` (its strategy is gone) or `failed`. */
    val status: String,
    val accounts: Int = 0,
    val transactions: Int = 0,
    val people: Int = 0,
    val duplicates: Int = 0,
    val errors: Int = 0,
    val error: String? = null,
) {
    companion object {
        const val FAILED = "failed"
        const val SKIPPED = "skipped"
    }
}

/** Something only a person can resolve: [kind] says what, [subject] names the file, card or session. */
@Serializable
internal data class NeedsAttentionJson(
    val kind: String,
    val subject: String,
    val reason: String,
)

internal fun ImportAllResult.toJson(): DirectoriesImportJson =
    DirectoriesImportJson(
        folders =
            download.results
                .map { (directory, scan) ->
                    FolderDownloadJson(
                        id = directory.id.toString(),
                        name = directory.name,
                        downloaded = scan.filesDownloaded,
                        unchanged = scan.filesUnchanged,
                        skipped = scan.filesSkipped,
                        failed = scan.filesFailed,
                        failures = scan.failures,
                    )
                }.sortedWith(compareBy({ it.name }, { it.id })),
        foldersCreated = download.created,
        redownload = redownload?.toJson(),
        files = csv.files.map { it.toJson() },
        failures = download.folderFailures,
    )

private fun DirectoryRedownloadResult.toJson() = RedownloadJson(replaced, unchanged, blocked, reimportSuggested, failures)

// Every staged file is a CSV import; a QIF file still reports as one.
private fun BulkFileOutcome.toJson() =
    FileImportJson(
        id = fileId,
        file = fileName,
        format = if (importFileKind(fileName) == ImportFileKind.QIF) "qif" else "csv",
        status =
            when (status) {
                BulkFileStatus.IMPORTED -> "imported"
                BulkFileStatus.NOTHING_TO_IMPORT -> "nothingToImport"
                BulkFileStatus.SKIPPED_NO_STRATEGY -> "skipped"
                BulkFileStatus.FAILED -> FileImportJson.FAILED
            },
        strategy = strategyName,
        transfers = transfersCreated,
        duplicates = duplicatesSkipped,
        error = error,
    )

internal fun ApiDownloadAndImportAllResult.toJson(): ApisImportJson =
    ApisImportJson(
        connections = credentials.map { it.toJson() },
        sessions = sessions.map { it.toJson() },
    )

private fun ApiCredentialDownload.toJson(): ConnectionDownloadJson {
    val outcome = download
    return ConnectionDownloadJson(
        id = credential.id.id,
        strategy = strategyName,
        status =
            when {
                error != null -> "failed"
                outcome is ApiDownloadOutcome.Downloaded -> ConnectionDownloadJson.DOWNLOADED
                outcome is ApiDownloadOutcome.NotDownloaded -> "notDownloaded"
                else -> "busy"
            },
        sessionId = (outcome as? ApiDownloadOutcome.Downloaded)?.sessionId?.id,
        message = error ?: outcome?.message,
    )
}

private fun ApiSessionImport.toJson() =
    SessionImportJson(
        id = session.id.id,
        connectionId = session.credentialId?.id,
        strategy = strategyName,
        status =
            when (status) {
                ApiSessionImportStatus.IMPORTED -> "imported"
                ApiSessionImportStatus.NEEDS_ATTENTION -> "needsAttention"
                ApiSessionImportStatus.SKIPPED_NO_STRATEGY -> SessionImportJson.SKIPPED
                ApiSessionImportStatus.FAILED -> SessionImportJson.FAILED
            },
        accounts = result?.accountCount ?: 0,
        transactions = result?.transactionCount ?: 0,
        people = result?.personCount ?: 0,
        duplicates = result?.duplicateCount ?: 0,
        errors = result?.errorCount ?: 0,
        error = error,
    )
