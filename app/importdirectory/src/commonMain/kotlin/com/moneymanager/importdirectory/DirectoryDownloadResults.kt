package com.moneymanager.importdirectory

import com.moneymanager.csvimporter.DirectoryRedownloadResult
import com.moneymanager.csvimporter.ScanResult
import com.moneymanager.domain.model.importdirectory.ImportDirectory
import com.moneymanager.tasks.formatElapsedTime
import kotlin.time.Duration

/** What one folder's download did. [createdSubfolders] is null for a folder that doesn't search for subfolders. */
data class DirectoryDownloadResult(
    val directory: ImportDirectory,
    val scan: ScanResult,
    val createdSubfolders: Int?,
) {
    fun summary(): String {
        val failedSuffix = if (scan.filesFailed > 0) ", ${scan.filesFailed} failed" else ""
        val created = createdSubfolders?.let { "; created $it subfolder director${if (it == 1) "y" else "ies"}" }.orEmpty()
        return "${directory.name}: downloaded ${scan.filesDownloaded} file(s)$failedSuffix$created."
    }
}

/**
 * What a "Download all" run did: [created] new subfolder directories, each downloaded folder's [results],
 * and [folderFailures] (a listing or subfolder search that threw, kept apart from per-file failures).
 */
data class DownloadAllResult(
    val created: Int,
    val results: List<Pair<ImportDirectory, ScanResult>>,
    val folderFailures: List<String>,
    val elapsed: Duration,
) {
    val filesDownloaded: Int get() = results.sumOf { (_, result) -> result.filesDownloaded }
    val filesFailed: Int get() = results.sumOf { (_, result) -> result.filesFailed }

    /** Every problem, prefixed with its directory so the same filename in two folders stays tellable apart. */
    val issues: List<String>
        get() = folderFailures + results.flatMap { (dir, result) -> result.failures.map { "${dir.name} — $it" } }

    fun summary(): String {
        val failedSuffix =
            listOfNotNull(
                "$filesFailed file(s) failed".takeIf { filesFailed > 0 },
                "${folderFailures.size} folder(s) failed".takeIf { folderFailures.isNotEmpty() },
            ).joinToString(separator = "") { "; $it" }
        return "Created $created new director${if (created == 1) "y" else "ies"}; downloaded $filesDownloaded file(s)$failedSuffix " +
            "in ${formatElapsedTime(elapsed)}."
    }
}

fun DirectoryRedownloadResult.summary(): String =
    buildString {
        append("Re-downloaded ${replaced.size + unimported.size} file(s)")
        if (unchanged.isNotEmpty()) append(", ${unchanged.size} unchanged")
        if (unimported.isNotEmpty()) append(", ${unimported.size} unimported first")
        if (blocked.isNotEmpty()) append(", ${blocked.size} skipped")
        if (failures.isNotEmpty()) append(", ${failures.size} failed")
        append(".")
    }

/** The failures plus what a person should do about the files a re-download skipped or changed. */
val DirectoryRedownloadResult.issues: List<String>
    get() =
        failures +
            blocked.map { "$it: imported rows are not in the new copy; re-download with \"unimport\" ticked" } +
            reimportSuggested.map { "$it: columns changed; Re-import it to apply them to its transactions" }
