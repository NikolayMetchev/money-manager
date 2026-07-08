@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.csvimporter

import com.moneymanager.csv.CsvParseOptions
import com.moneymanager.csv.CsvParser
import com.moneymanager.domain.model.csv.CsvImportId
import com.moneymanager.domain.model.importdirectory.ImportDirectory
import com.moneymanager.domain.model.qif.QifImportId
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.domain.repository.ImportDirectoryReadRepository
import com.moneymanager.domain.repository.QifImportReadRepository
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.createCsvImport
import com.moneymanager.importengineapi.createQifImport
import com.moneymanager.importengineapi.recordDirectoryFileImported
import com.moneymanager.importfilesource.ImportFileEntry
import com.moneymanager.importfilesource.ImportFileSource
import com.moneymanager.qif.QifParser
import org.lighthousegames.logging.logging
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant

private val scanLogger = logging()

/** File types the scanner can stage. Others (e.g. .pdf) are skipped. */
private enum class SupportedKind { CSV, QIF }

private fun supportedKind(fileName: String): SupportedKind? =
    when (fileName.substringAfterLast('.', "").lowercase()) {
        "csv" -> SupportedKind.CSV
        "qif" -> SupportedKind.QIF
        else -> null
    }

/** True if [fileName] is an importable file (.csv/.qif). Used by directory discovery. */
internal fun isSupportedImportFile(fileName: String): Boolean = supportedKind(fileName) != null

/**
 * Scans one configured [directory]: lists its files and DOWNLOADS new/changed **supported** files
 * (.csv → CSV staging, .qif → QIF staging) into the Imports section. Unsupported files (e.g. .pdf) are
 * skipped without downloading. It does NOT apply a strategy — the user runs the existing "Import All".
 * Change detection: a matching server-provided content hash (e.g. Drive md5Checksum) skips the download
 * entirely; otherwise the file is downloaded and sha256-confirmed. A changed file produces a fresh
 * staging row; a bad file is recorded as a failure and does not abort the scan.
 */
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
suspend fun scanImportDirectory(
    directory: ImportDirectory,
    fileSource: ImportFileSource,
    importDirectoryRepository: ImportDirectoryReadRepository,
    csvImportRepository: CsvImportReadRepository,
    qifImportRepository: QifImportReadRepository,
    importEngine: ImportEngine,
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
): ScanResult {
    val entries = fileSource.list()
    var downloaded = 0
    var csvDownloaded = 0
    var qifDownloaded = 0
    var unchanged = 0
    var skipped = 0
    var failed = 0
    val failures = mutableListOf<String>()

    entries.forEachIndexed { index, entry ->
        onProgress(index, entries.size)
        val kind = supportedKind(entry.name)
        if (kind == null) {
            // Don't even download unsupported files (e.g. PDFs).
            skipped++
            return@forEachIndexed
        }
        try {
            val tracked = importDirectoryRepository.getTrackedFile(directory.id, entry.ref)
            val lastModified = entry.lastModifiedInstant()

            // Incremental skip: a server-provided content hash (e.g. Drive md5Checksum) that matches the
            // last import means the bytes are unchanged, so there is no need to download the file at all.
            // Only remote backends supply this; local entries have a null hash and fall through to
            // download + sha256 below (cheap, no network — and robust against preserved timestamps).
            val remoteContentHash = entry.remoteContentHash
            if (remoteContentHash != null && tracked?.remoteContentHash == remoteContentHash) {
                importEngine.recordDirectoryFileImported(
                    directoryId = directory.id,
                    fileRef = entry.ref,
                    fileName = entry.name,
                    lastModified = lastModified,
                    checksum = tracked.checksum,
                    remoteContentHash = remoteContentHash,
                    csvImportId = tracked.csvImportId,
                    qifImportId = tracked.qifImportId,
                    importedAt = Clock.System.now(),
                )
                unchanged++
                return@forEachIndexed
            }

            // Always hash the content: a provider that preserves/backdates the timestamp on an edit
            // would otherwise hide a real content change forever. The checksum below decides re-staging.
            val content = fileSource.download(entry.ref).decodeToString()
            val checksum = sha256Hex(content)

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
                    qifImportId = tracked.qifImportId,
                    importedAt = Clock.System.now(),
                )
                unchanged++
                return@forEachIndexed
            }

            var csvImportId: CsvImportId? = null
            var qifImportId: QifImportId? = null
            when (kind) {
                SupportedKind.CSV -> {
                    csvImportId =
                        csvImportRepository.findImportsByChecksum(checksum).firstOrNull()?.id
                            ?: stageCsv(importEngine, entry, content, checksum, lastModified)
                    csvDownloaded++
                }
                SupportedKind.QIF -> {
                    qifImportId =
                        qifImportRepository.findImportsByChecksum(checksum).firstOrNull()?.id
                            ?: stageQif(importEngine, entry, content, checksum, lastModified)
                    qifDownloaded++
                }
            }

            importEngine.recordDirectoryFileImported(
                directoryId = directory.id,
                fileRef = entry.ref,
                fileName = entry.name,
                lastModified = lastModified,
                checksum = checksum,
                remoteContentHash = remoteContentHash,
                csvImportId = csvImportId,
                qifImportId = qifImportId,
                importedAt = Clock.System.now(),
            )
            downloaded++
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (expected: Exception) {
            scanLogger.error(expected) { "Scan failed for ${entry.name} in '${directory.name}': ${expected.message}" }
            failed++
            failures.add("${entry.name}: ${expected.message}")
        }
    }

    onProgress(entries.size, entries.size)

    return ScanResult(
        filesDownloaded = downloaded,
        csvDownloaded = csvDownloaded,
        qifDownloaded = qifDownloaded,
        filesUnchanged = unchanged,
        filesSkipped = skipped,
        filesFailed = failed,
        failures = failures,
    )
}

private suspend fun stageCsv(
    importEngine: ImportEngine,
    entry: ImportFileEntry,
    content: String,
    checksum: String,
    lastModified: Instant,
): CsvImportId {
    val parser = CsvParser()
    val delimiter = parser.detectDelimiter(content)
    val parsed = parser.parse(content, CsvParseOptions(delimiter = delimiter))
    return importEngine.createCsvImport(
        fileName = entry.name,
        headers = parsed.headers,
        rows = parsed.rows,
        fileChecksum = checksum,
        fileLastModified = lastModified,
    )
}

private suspend fun stageQif(
    importEngine: ImportEngine,
    entry: ImportFileEntry,
    content: String,
    checksum: String,
    lastModified: Instant,
): QifImportId {
    val parsed = QifParser().parse(content)
    return importEngine.createQifImport(
        fileName = entry.name,
        records = parsed.toStagingRecords(),
        accountType = parsed.dominantAccountTypeOrUnknown(),
        fileChecksum = checksum,
        fileLastModified = lastModified,
    )
}

private fun ImportFileEntry.lastModifiedInstant(): Instant = lastModifiedEpochMs?.let(Instant::fromEpochMilliseconds) ?: Clock.System.now()
