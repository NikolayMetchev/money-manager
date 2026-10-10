package com.moneymanager.csvimporter

import co.touchlab.kermit.Logger
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.QifImportId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.importdirectory.ImportDirectory
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.domain.repository.ImportDirectoryReadRepository
import com.moneymanager.domain.repository.QifImportReadRepository
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.createAccount
import com.moneymanager.importengineapi.createCsvImport
import com.moneymanager.importengineapi.createQifImport
import com.moneymanager.importengineapi.createXlsxImport
import com.moneymanager.importengineapi.recordDirectoryFileImported
import com.moneymanager.importengineapi.updateImportDirectory
import com.moneymanager.importfilesource.ImportFileEntry
import com.moneymanager.importfilesource.ImportFileSource
import com.moneymanager.qif.QifParser
import com.moneymanager.xlsx.createXlsxParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant

private val scanLogger = Logger.withTag("ImportDirectoryScanner")

/** File types the scanner can stage. Others (e.g. .pdf) are skipped. */
private enum class SupportedKind { CSV, QIF, XLSX }

private fun supportedKind(fileName: String): SupportedKind? =
    when (fileName.substringAfterLast('.', "").lowercase()) {
        "csv" -> SupportedKind.CSV
        "qif" -> SupportedKind.QIF
        "xlsx" -> SupportedKind.XLSX
        else -> null
    }

/** True if [fileName] is an importable file (.csv/.qif/.xlsx). Used by directory discovery. */
internal fun isSupportedImportFile(fileName: String): Boolean = supportedKind(fileName) != null

/**
 * Scans one configured [directory]: lists its files and DOWNLOADS new/changed **supported** files
 * (.csv → CSV staging, .qif → QIF staging, .xlsx → CSV staging of its first worksheet) into the
 * Imports section. Unsupported files (e.g. .pdf) are skipped without downloading. Excel parsing (Apache
 * POI) is JVM-only; on Android an .xlsx file is recorded as a per-file scan failure rather than crashing
 * the scan. It does NOT apply a strategy — the user runs the existing "Import All".
 * Change detection: a matching server-provided content hash (e.g. Drive md5Checksum) skips the download
 * entirely; otherwise the file is downloaded and sha256-confirmed. A changed file produces a fresh
 * staging row; a bad file is recorded as a failure and does not abort the scan.
 *
 * Every file is scanned in its own coroutine on [Dispatchers.IO], so downloads run concurrently. The
 * engine doesn't serialize writes itself, so every database step holds [dbLock]; callers scanning
 * several directories at once must share one lock between them.
 *
 * A top-level [directory] with no [ImportDirectory.accountId] set gets one resolved automatically
 * before scanning: an existing account named exactly [ImportDirectory.name] if one exists, else a
 * newly created one (see [ensureDirectoryAccount]) — the user can still override it via "Set account".
 * Discovered subfolders (`topLevel == false`) are left alone; they inherit their parent's account.
 */
@Suppress("LongParameterList")
suspend fun scanImportDirectory(
    directory: ImportDirectory,
    fileSource: ImportFileSource,
    importDirectoryRepository: ImportDirectoryReadRepository,
    csvImportRepository: CsvImportReadRepository,
    qifImportRepository: QifImportReadRepository,
    importEngine: ImportEngine,
    dbLock: Mutex = Mutex(),
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
): ScanResult {
    if (directory.topLevel && directory.accountId == null) {
        dbLock.withLock { ensureDirectoryAccount(directory, importEngine) }
    }
    val entries = fileSource.list()
    val context =
        ScanContext(directory, fileSource, importDirectoryRepository, csvImportRepository, qifImportRepository, importEngine, dbLock)
    val progressLock = Mutex()
    var done = 0
    onProgress(0, entries.size)

    val outcomes =
        coroutineScope {
            entries
                .map { entry ->
                    async(Dispatchers.IO) {
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
    val qifImportRepository: QifImportReadRepository,
    val importEngine: ImportEngine,
    val dbLock: Mutex,
)

@Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
private suspend fun ScanContext.scanFile(entry: ImportFileEntry): FileOutcome {
    // Don't even download unsupported files (e.g. PDFs).
    val kind = supportedKind(entry.name) ?: return FileOutcome.Skipped
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
                        qifImportId = tracked.qifImportId,
                        importedAt = Clock.System.now(),
                    )
                    return FileOutcome.Unchanged
                }
                tracked
            }

        // Always hash the content: a provider that preserves/backdates the timestamp on an edit
        // would otherwise hide a real content change forever. The checksum below decides re-staging.
        // Excel is binary and must be hashed/staged from raw bytes; decoding it as UTF-8 text (like
        // CSV/QIF) would both corrupt the checksum and lose data, so it branches before decoding.
        val rawBytes = fileSource.download(entry.ref)
        val content = if (kind == SupportedKind.XLSX) null else rawBytes.decodeToString()
        val checksum = content?.let(::sha256Hex) ?: sha256Hex(rawBytes)

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
                    qifImportId = tracked.qifImportId,
                    importedAt = Clock.System.now(),
                )
                return FileOutcome.Unchanged
            }

            var csvImportId: CsvImportId? = null
            var qifImportId: QifImportId? = null
            when (kind) {
                SupportedKind.CSV -> {
                    csvImportId =
                        csvImportRepository.findImportsByChecksum(checksum).firstOrNull()?.id
                            ?: stageCsv(importEngine, entry, checkNotNull(content), checksum, lastModified)
                }
                SupportedKind.QIF -> {
                    qifImportId =
                        qifImportRepository.findImportsByChecksum(checksum).firstOrNull()?.id
                            ?: stageQif(importEngine, entry, checkNotNull(content), checksum, lastModified)
                }
                SupportedKind.XLSX -> {
                    csvImportId =
                        csvImportRepository.findImportsByChecksum(checksum).firstOrNull()?.id
                            ?: stageXlsx(importEngine, entry, rawBytes, checksum, lastModified)
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
        }
        FileOutcome.Downloaded
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (expected: Exception) {
        scanLogger.e(expected) { "Scan failed for ${entry.name} in '${directory.name}': ${expected.message}" }
        FileOutcome.Failed("${entry.name}: ${expected.message}")
    }
}

private suspend fun stageCsv(
    importEngine: ImportEngine,
    entry: ImportFileEntry,
    content: String,
    checksum: String,
    lastModified: Instant,
): CsvImportId {
    val parsed = parseStagedCsv(content)
    return importEngine.createCsvImport(
        fileName = entry.name,
        headers = parsed.headers,
        rows = parsed.rows,
        fileChecksum = checksum,
        fileLastModified = lastModified,
    )
}

/**
 * Stages an Excel workbook's first worksheet exactly like [stageCsv] (header row + data rows), plus the
 * raw workbook bytes so a strategy naming a different worksheet can re-extract it later. Throws
 * `XlsxUnsupportedPlatformException` on Android (Apache POI is JVM-only); the caller's generic
 * exception handler records that as a per-file scan failure.
 */
private suspend fun stageXlsx(
    importEngine: ImportEngine,
    entry: ImportFileEntry,
    bytes: ByteArray,
    checksum: String,
    lastModified: Instant,
): CsvImportId {
    val parser = createXlsxParser()
    val sheetName = parser.sheetNames(bytes).firstOrNull() ?: ""
    val parsed = parser.parse(bytes, sheetName)
    return importEngine.createXlsxImport(
        fileName = entry.name,
        headers = parsed.headers,
        rows = parsed.rows,
        fileChecksum = checksum,
        fileLastModified = lastModified,
        xlsxBytes = bytes,
        xlsxWorksheetName = sheetName,
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

/**
 * Resolves [directory]'s account by name — an existing account named exactly [ImportDirectory.name],
 * or a newly created one — and persists it onto the directory. [ImportEngine.createAccount] already
 * matches by name before creating (idempotent), so this never creates a second account for a name
 * that already exists; it only needs to decide whether the directory should point at it.
 */
private suspend fun ensureDirectoryAccount(
    directory: ImportDirectory,
    importEngine: ImportEngine,
) {
    val accountId =
        importEngine.createAccount(
            Account(id = AccountId(0), name = directory.name, openingDate = Clock.System.now()),
            Source.System,
        )
    importEngine.updateImportDirectory(directory.copy(accountId = accountId), Source.System)
}
