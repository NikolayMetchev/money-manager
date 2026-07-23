package com.moneymanager.csvimporter

import com.moneymanager.csv.CsvParseOptions
import com.moneymanager.csv.CsvParser
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
import org.lighthousegames.logging.logging
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant

private val scanLogger = logging()

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
 * A top-level [directory] with no [ImportDirectory.accountId] set gets one resolved automatically
 * before scanning: an existing account named exactly [ImportDirectory.name] if one exists, else a
 * newly created one (see [ensureDirectoryAccount]) — the user can still override it via "Set account".
 * Discovered subfolders (`topLevel == false`) are left alone; they inherit their parent's account.
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
    if (directory.topLevel && directory.accountId == null) {
        ensureDirectoryAccount(directory, importEngine)
    }
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
            // Excel is binary and must be hashed/staged from raw bytes; decoding it as UTF-8 text (like
            // CSV/QIF) would both corrupt the checksum and lose data, so it branches before decoding.
            val rawBytes = fileSource.download(entry.ref)
            val content = if (kind == SupportedKind.XLSX) null else rawBytes.decodeToString()
            val checksum = content?.let(::sha256Hex) ?: sha256Hex(rawBytes)

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
                            ?: stageCsv(importEngine, entry, checkNotNull(content), checksum, lastModified)
                    csvDownloaded++
                }
                SupportedKind.QIF -> {
                    qifImportId =
                        qifImportRepository.findImportsByChecksum(checksum).firstOrNull()?.id
                            ?: stageQif(importEngine, entry, checkNotNull(content), checksum, lastModified)
                    qifDownloaded++
                }
                SupportedKind.XLSX -> {
                    csvImportId =
                        csvImportRepository.findImportsByChecksum(checksum).firstOrNull()?.id
                            ?: stageXlsx(importEngine, entry, rawBytes, checksum, lastModified)
                    csvDownloaded++
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
