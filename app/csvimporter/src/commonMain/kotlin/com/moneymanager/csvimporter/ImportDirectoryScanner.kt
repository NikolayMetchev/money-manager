package com.moneymanager.csvimporter

import co.touchlab.kermit.Logger
import com.moneymanager.coroutines.ioDispatcher
import com.moneymanager.domain.model.importFileKind
import com.moneymanager.domain.model.importdirectory.ImportDirectory
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.domain.repository.ImportDirectoryReadRepository
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.recordDirectoryFileImported
import com.moneymanager.importfilesource.ImportFileEntry
import com.moneymanager.importfilesource.ImportFileSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant

private val scanLogger = Logger.withTag("ImportDirectoryScanner")

/** True if [fileName] is an importable file (.csv/.qif/.xlsx/.pdf). Used by directory discovery. */
internal fun isSupportedImportFile(fileName: String): Boolean = importFileKind(fileName) != null

/**
 * Scans one configured [directory]: lists its files and DOWNLOADS new/changed **supported** files
 * (.csv → CSV staging, .qif → CSV staging of its records, .xlsx → CSV staging of its first worksheet, .pdf → CSV staging
 * of its text lines) into the Imports section. Unsupported files are skipped without downloading. Excel
 * (Apache POI) and PDF (pdfium) parsing are JVM-only; elsewhere such a file is recorded as a per-file scan
 * failure rather than crashing the scan. It does NOT apply a strategy — the user runs the existing "Import All".
 * Change detection: a matching server-provided content hash (e.g. Drive md5Checksum) skips the download
 * entirely; otherwise the file is downloaded and sha256-confirmed. A changed file produces a fresh
 * staging row; a bad file is recorded as a failure and does not abort the scan.
 *
 * Every file is scanned in its own coroutine on [ioDispatcher], so downloads run concurrently. The
 * engine doesn't serialize writes itself, so every database step holds [dbLock]; callers scanning
 * several directories at once must share one lock between them.
 */
@Suppress("LongParameterList")
suspend fun scanImportDirectory(
    directory: ImportDirectory,
    fileSource: ImportFileSource,
    importDirectoryRepository: ImportDirectoryReadRepository,
    csvImportRepository: CsvImportReadRepository,
    importEngine: ImportEngine,
    dbLock: Mutex = Mutex(),
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
): ScanResult {
    val entries = fileSource.list()
    val context =
        ScanContext(directory, fileSource, importDirectoryRepository, csvImportRepository, importEngine, dbLock)
    val progressLock = Mutex()
    var done = 0
    onProgress(0, entries.size)

    val outcomes =
        coroutineScope {
            entries
                .map { entry ->
                    async(ioDispatcher) {
                        context.scanFile(entry).also { progressLock.withLock { onProgress(++done, entries.size) } }
                    }
                }.awaitAll()
        }

    val failures = outcomes.filterIsInstance<FileOutcome.Failed>().map { it.message }
    return ScanResult(
        filesDownloaded = outcomes.count { it == FileOutcome.Downloaded },
        filesUnchanged = outcomes.count { it == FileOutcome.Unchanged },
        filesSkipped = outcomes.count { it == FileOutcome.Skipped },
        filesFailed = failures.size,
        failures = failures,
    )
}

private sealed interface FileOutcome {
    data object Downloaded : FileOutcome

    data object Unchanged : FileOutcome

    data object Skipped : FileOutcome

    data class Failed(
        val message: String,
    ) : FileOutcome
}

private class ScanContext(
    val directory: ImportDirectory,
    val fileSource: ImportFileSource,
    val importDirectoryRepository: ImportDirectoryReadRepository,
    val csvImportRepository: CsvImportReadRepository,
    val importEngine: ImportEngine,
    val dbLock: Mutex,
)

@Suppress("LongMethod", "ReturnCount")
private suspend fun ScanContext.scanFile(entry: ImportFileEntry): FileOutcome {
    // Don't even download unsupported files.
    val kind = importFileKind(entry.name) ?: return FileOutcome.Skipped
    return try {
        val lastModified = entry.lastModifiedInstant()

        // Incremental skip: a server-provided content hash (e.g. Drive md5Checksum) that matches the
        // last import means the bytes are unchanged, so there is no need to download the file at all.
        // Only remote backends supply this; local entries have a null hash and fall through to
        // download + sha256 below (cheap, no network — and robust against preserved timestamps).
        val remoteContentHash = entry.remoteContentHash
        val tracked =
            dbLock.withLock {
                val tracked = importDirectoryRepository.getTrackedFile(directory.id, entry.ref)
                if (remoteContentHash != null && tracked?.remoteContentHash == remoteContentHash) {
                    importEngine.recordDirectoryFileImported(
                        directoryId = directory.id,
                        fileRef = entry.ref,
                        fileName = entry.name,
                        lastModified = lastModified,
                        checksum = tracked.checksum,
                        remoteContentHash = remoteContentHash,
                        csvImportId = tracked.csvImportId,
                        importedAt = Clock.System.now(),
                    )
                    return FileOutcome.Unchanged
                }
                tracked
            }

        // Always hash the content: a provider that preserves/backdates the timestamp on an edit
        // would otherwise hide a real content change forever. The checksum below decides re-staging.
        val rawBytes = fileSource.download(entry.ref)
        val checksum = importFileChecksum(kind, rawBytes)

        dbLock.withLock {
            // Content unchanged despite a moved timestamp: advance the cursor, don't re-stage.
            if (tracked?.checksum == checksum) {
                importEngine.recordDirectoryFileImported(
                    directoryId = directory.id,
                    fileRef = entry.ref,
                    fileName = entry.name,
                    lastModified = lastModified,
                    checksum = checksum,
                    remoteContentHash = remoteContentHash,
                    csvImportId = tracked.csvImportId,
                    importedAt = Clock.System.now(),
                )
                return FileOutcome.Unchanged
            }

            val csvImportId =
                importEngine.stageImportFile(kind, entry.name, rawBytes, checksum, lastModified, csvImportRepository)

            importEngine.recordDirectoryFileImported(
                directoryId = directory.id,
                fileRef = entry.ref,
                fileName = entry.name,
                lastModified = lastModified,
                checksum = checksum,
                remoteContentHash = remoteContentHash,
                csvImportId = csvImportId,
                importedAt = Clock.System.now(),
            )
        }
        FileOutcome.Downloaded
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (expected: Exception) {
        scanLogger.e(expected) { "Scan failed for ${entry.name} in '${directory.name}': ${expected.message}" }
        FileOutcome.Failed("${entry.name}: ${expected.message}")
    }
}

private fun ImportFileEntry.lastModifiedInstant(): Instant = lastModifiedEpochMs?.let(Instant::fromEpochMilliseconds) ?: Clock.System.now()
