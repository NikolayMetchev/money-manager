package com.moneymanager.csvimporter

import com.moneymanager.csv.CsvParseOptions
import com.moneymanager.csv.CsvParseResult
import com.moneymanager.csv.CsvParser
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.ImportDirectoryId
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csv.CsvRowLink
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.importdirectory.ImportDirectory
import com.moneymanager.domain.model.importdirectory.ImportDirectoryFile
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.domain.repository.ImportDirectoryReadRepository
import com.moneymanager.importengineapi.CsvImportMutation
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.recordDirectoryFileImported
import com.moneymanager.importfilesource.ImportFileEntry
import com.moneymanager.importfilesource.ImportFileSource
import com.moneymanager.importfilesource.ImportFileSourceFactory
import com.moneymanager.xlsx.createXlsxParser
import kotlinx.coroutines.flow.first
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * What re-staging a file in place from a fresh parse will do (see [planCsvRedownload]). A preview for the
 * confirmation dialog, executed verbatim by [executeCsvRedownload].
 */
sealed interface CsvRedownloadPlan {
    /** The fresh parse is identical to what is staged; nothing to do. */
    data object Unchanged : CsvRedownloadPlan

    /**
     * The staged table is replaced. Rows of an already-imported file keep their outcome and provenance on
     * the new row they were matched to ([rowIndexRemap], old -> new); new rows arrive never-imported.
     */
    data class Replace(
        val oldHeaders: List<String>,
        val newHeaders: List<String>,
        val rowIndexRemap: Map<Long, Long>,
        val carriedRows: List<CsvRowLink>,
        /** Old rows with no counterpart in the new file; none of them was ever imported. */
        val droppedRows: Int,
        val newRowCount: Int,
    ) : CsvRedownloadPlan {
        val addedColumns: List<String> get() = newHeaders - oldHeaders.toSet()
        val removedColumns: List<String> get() = oldHeaders - newHeaders.toSet()
        val matchedRows: Int get() = rowIndexRemap.size
        val newRows: Int get() = newRowCount - matchedRows
    }

    /**
     * Some imported rows have no counterpart in the new file — it no longer has them, or the old and new
     * parses share no column to recognise them by. Re-staging would orphan their transactions, so the
     * file must be unimported first.
     */
    data class Blocked(
        val unmatchedImportedRows: List<CsvRow>,
    ) : CsvRedownloadPlan
}

/** A downloaded, parsed copy of a staged file, plus what is needed to re-stage it. */
class CsvRedownload internal constructor(
    val importId: CsvImportId,
    val headers: List<String>,
    val rows: List<List<String>>,
    val checksum: String,
    val lastModified: Instant,
    val xlsxBytes: ByteArray?,
    val plan: CsvRedownloadPlan,
    internal val source: TrackedSource,
)

/** The import-folder file a staged import came from. */
internal data class TrackedSource(
    val directoryId: ImportDirectoryId,
    val fileRef: String,
    val fileName: String,
    val remoteContentHash: String?,
)

/** Why a file cannot be re-downloaded. */
class CsvRedownloadException(
    message: String,
) : Exception(message)

private fun redownloadFailure(message: String): Nothing = throw CsvRedownloadException(message)

/**
 * Matches the rows of a fresh parse ([headers], [rows]) against what is staged for [staged] ([oldRows]).
 *
 * Rows are recognised by their values in the columns both parses share, by name; the first unclaimed old
 * row with the same values wins, so repeated identical rows pair up in order. A file nobody imported is
 * simply replaced, whatever its columns — that is the repair for a file the parser once read wrongly.
 * An imported row must find its counterpart, or the plan is [CsvRedownloadPlan.Blocked].
 */
fun planCsvRedownload(
    staged: CsvImport,
    oldRows: List<CsvRow>,
    headers: List<String>,
    rows: List<List<String>>,
    checksum: String = staged.fileChecksum,
): CsvRedownloadPlan {
    val oldHeaders = staged.columns.sortedBy { it.columnIndex }.map { it.originalName }
    if (checksum == staged.fileChecksum &&
        oldHeaders == headers &&
        oldRows.map { it.values.padded(headers.size) } == rows.map { it.padded(headers.size) }
    ) {
        return CsvRedownloadPlan.Unchanged
    }

    val shared = headers.filter { it in oldHeaders }.distinct()
    val oldPositions = shared.map { oldHeaders.indexOf(it) }
    val newPositions = shared.map { headers.indexOf(it) }
    val unclaimed: MutableMap<List<String>, ArrayDeque<CsvRow>> =
        oldRows
            .sortedBy { it.rowIndex }
            .groupByTo(LinkedHashMap()) { row -> oldPositions.map { row.values.getOrElse(it) { "" } } }
            .mapValuesTo(LinkedHashMap()) { ArrayDeque(it.value) }

    val remap = LinkedHashMap<Long, Long>()
    val carried = mutableListOf<CsvRowLink>()
    if (shared.isNotEmpty()) {
        rows.forEachIndexed { position, row ->
            val key = newPositions.map { row.getOrElse(it) { "" } }
            val old = unclaimed[key]?.removeFirstOrNull() ?: return@forEachIndexed
            // The new table numbers its rows from 1 in file order.
            val newIndex = position + 1L
            remap[old.rowIndex] = newIndex
            old.carriedStatus()?.let { carried += CsvRowLink(newIndex, it, old.transferId) }
        }
    }

    val unmatchedImported = oldRows.filter { it.rowIndex !in remap && it.carriedStatus() != null }
    if (unmatchedImported.isNotEmpty()) return CsvRedownloadPlan.Blocked(unmatchedImported)

    return CsvRedownloadPlan.Replace(
        oldHeaders = oldHeaders,
        newHeaders = headers,
        rowIndexRemap = remap,
        carriedRows = carried,
        droppedRows = oldRows.size - remap.size,
        newRowCount = rows.size,
    )
}

/** An outcome worth keeping: errors are re-derived by the next import, so only real outcomes carry. */
private fun CsvRow.carriedStatus(): ImportStatus? = importStatus?.takeIf { it != ImportStatus.ERROR }

private fun List<String>.padded(size: Int): List<String> = if (this.size >= size) take(size) else this + List(size - this.size) { "" }

/** Parses CSV text exactly as staging does: the delimiter is detected, then the file is read with headers. */
internal fun parseStagedCsv(content: String): CsvParseResult {
    val parser = CsvParser()
    return parser.parse(content, CsvParseOptions(delimiter = parser.detectDelimiter(content)))
}

/**
 * Downloads [importId]'s file again from the import folder it was staged from, parses it with the
 * current parser, and plans how to re-stage it ([planCsvRedownload]). Nothing is written.
 */
suspend fun prepareCsvRedownload(
    importId: CsvImportId,
    csvImportRepository: CsvImportReadRepository,
    importDirectoryRepository: ImportDirectoryReadRepository,
    fileSourceFactory: ImportFileSourceFactory,
): CsvRedownload {
    val staged =
        csvImportRepository.getImport(importId).first() ?: redownloadFailure("The import no longer exists.")
    val tracked =
        importDirectoryRepository.getTrackedFilesForCsvImport(importId).firstOrNull()
            ?: redownloadFailure("${staged.originalFileName} was not staged from an import folder.")
    val directory =
        importDirectoryRepository.getDirectoryById(tracked.directoryId).first()
            ?: redownloadFailure("The import folder for ${staged.originalFileName} no longer exists.")
    if (!fileSourceFactory.supportsProvider(directory.provider)) {
        redownloadFailure("This device cannot read ${directory.provider} folders.")
    }
    val source = fileSourceFactory.create(directory)
    return prepareFromSource(staged, tracked, source, source.list(), csvImportRepository)
}

/** Downloads [tracked] from the already-open [source] (whose listing is [entries]) and plans its re-stage. */
private suspend fun prepareFromSource(
    staged: CsvImport,
    tracked: ImportDirectoryFile,
    source: ImportFileSource,
    entries: List<ImportFileEntry>,
    csvImportRepository: CsvImportReadRepository,
): CsvRedownload {
    val importId = staged.id
    val entry =
        entries.firstOrNull { it.ref == tracked.fileRef } ?: redownloadFailure("${tracked.fileName} is no longer in the folder.")
    val bytes = source.download(tracked.fileRef)

    val blob = csvImportRepository.getXlsxBlob(importId)
    val (parsed, checksum) =
        if (blob != null) {
            createXlsxParser().parse(bytes, blob.worksheetName) to sha256Hex(bytes)
        } else {
            val content = bytes.decodeToString()
            parseStagedCsv(content) to sha256Hex(content)
        }

    val other = csvImportRepository.findImportsByChecksum(checksum).firstOrNull { it.id != importId }
    if (other != null) {
        redownloadFailure("the folder's current copy is already staged as ${other.originalFileName}.")
    }

    val oldRows = csvImportRepository.getImportRows(importId, limit = maxOf(staged.rowCount, 1), offset = 0)
    return CsvRedownload(
        importId = importId,
        headers = parsed.headers,
        rows = parsed.rows,
        checksum = checksum,
        lastModified = entry.lastModifiedEpochMs?.let(Instant::fromEpochMilliseconds) ?: Clock.System.now(),
        xlsxBytes = bytes.takeIf { blob != null },
        plan = planCsvRedownload(staged, oldRows, parsed.headers, parsed.rows, checksum),
        source = TrackedSource(tracked.directoryId, tracked.fileRef, tracked.fileName, entry.remoteContentHash),
    )
}

/** What re-downloading every staged CSV of one import folder did; file names in each list. */
data class DirectoryRedownloadResult(
    val replaced: List<String> = emptyList(),
    val unchanged: List<String> = emptyList(),
    /** Imported rows the new copy doesn't have; left as they were (see [redownloadImportDirectory]). */
    val blocked: List<String> = emptyList(),
    /** Blocked files that were unimported and then re-downloaded fresh. */
    val unimported: List<String> = emptyList(),
    /** Kept their transactions while their columns changed: a Re-import would apply the new values. */
    val reimportSuggested: List<String> = emptyList(),
    /** "file name: reason" for each file that could not be re-downloaded. */
    val failures: List<String> = emptyList(),
) {
    operator fun plus(other: DirectoryRedownloadResult) =
        DirectoryRedownloadResult(
            replaced + other.replaced,
            unchanged + other.unchanged,
            blocked + other.blocked,
            unimported + other.unimported,
            reimportSuggested + other.reimportSuggested,
            failures + other.failures,
        )
}

/**
 * Re-downloads every CSV/Excel file [directory] has staged and re-stages each one in place with the
 * current parser ([planCsvRedownload] decides how). A file whose imported rows the new copy no longer has
 * is left untouched and reported as blocked — unless [unimport] is given, in which case it is unimported
 * with it first and then re-staged fresh. One bad file is reported and does not stop the rest.
 */
@Suppress("LongParameterList")
suspend fun redownloadImportDirectory(
    directory: ImportDirectory,
    fileSource: ImportFileSource,
    importDirectoryRepository: ImportDirectoryReadRepository,
    csvImportRepository: CsvImportReadRepository,
    importEngine: ImportEngine,
    unimport: (suspend (CsvImportId) -> Unit)? = null,
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
): DirectoryRedownloadResult {
    val tracked = importDirectoryRepository.getTrackedFiles(directory.id).first().filter { it.csvImportId != null }
    onProgress(0, tracked.size)
    if (tracked.isEmpty()) return DirectoryRedownloadResult()
    val entries = fileSource.list()
    var result = DirectoryRedownloadResult()
    tracked.forEachIndexed { index, file ->
        result += redownloadOne(file, fileSource, entries, csvImportRepository, importEngine, unimport)
        onProgress(index + 1, tracked.size)
    }
    return result
}

@Suppress("LongParameterList", "ReturnCount")
private suspend fun redownloadOne(
    file: ImportDirectoryFile,
    fileSource: ImportFileSource,
    entries: List<ImportFileEntry>,
    csvImportRepository: CsvImportReadRepository,
    importEngine: ImportEngine,
    unimport: (suspend (CsvImportId) -> Unit)?,
): DirectoryRedownloadResult {
    val name = file.fileName
    return try {
        val staged = csvImportRepository.getImport(checkNotNull(file.csvImportId)).first() ?: return DirectoryRedownloadResult()
        val redownload = prepareFromSource(staged, file, fileSource, entries, csvImportRepository)
        when (val plan = redownload.plan) {
            CsvRedownloadPlan.Unchanged -> DirectoryRedownloadResult(unchanged = listOf(name))
            is CsvRedownloadPlan.Replace -> {
                executeCsvRedownload(redownload, importEngine)
                val columnsChanged = plan.addedColumns.isNotEmpty() || plan.removedColumns.isNotEmpty()
                DirectoryRedownloadResult(
                    replaced = listOf(name),
                    reimportSuggested = listOf(name).filter { plan.carriedRows.isNotEmpty() && columnsChanged },
                )
            }
            is CsvRedownloadPlan.Blocked -> {
                if (unimport == null) return DirectoryRedownloadResult(blocked = listOf(name))
                unimport(staged.id)
                val fresh =
                    prepareFromSource(
                        checkNotNull(csvImportRepository.getImport(staged.id).first()),
                        file,
                        fileSource,
                        entries,
                        csvImportRepository,
                    )
                executeCsvRedownload(fresh, importEngine)
                DirectoryRedownloadResult(unimported = listOf(name))
            }
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (expected: Exception) {
        DirectoryRedownloadResult(failures = listOf("$name: ${expected.message}"))
    }
}

/**
 * Re-stages [redownload] in place per its plan and moves the folder's change-detection cursor to the
 * downloaded content, so the next scan does not stage it a second time. A [CsvRedownloadPlan.Blocked]
 * plan is refused; unimport the file and prepare again.
 */
suspend fun executeCsvRedownload(
    redownload: CsvRedownload,
    importEngine: ImportEngine,
) {
    val plan = redownload.plan
    if (plan is CsvRedownloadPlan.Blocked) {
        throw CsvRedownloadException("${plan.unmatchedImportedRows.size} imported rows are not in the new file; unimport it first.")
    }
    if (plan is CsvRedownloadPlan.Replace) {
        importEngine.import(
            ImportBatch(
                csvImportMutations =
                    listOf(
                        CsvImportMutation.Repopulate(
                            id = redownload.importId,
                            headers = redownload.headers,
                            rows = redownload.rows,
                            fileChecksum = redownload.checksum,
                            fileLastModified = redownload.lastModified,
                            carriedRows = plan.carriedRows,
                            rowIndexRemap = plan.rowIndexRemap,
                            xlsxBytes = redownload.xlsxBytes,
                        ),
                    ),
            ),
        )
    }
    val source = redownload.source
    importEngine.recordDirectoryFileImported(
        directoryId = source.directoryId,
        fileRef = source.fileRef,
        fileName = source.fileName,
        lastModified = redownload.lastModified,
        checksum = redownload.checksum,
        remoteContentHash = source.remoteContentHash,
        csvImportId = redownload.importId,
        qifImportId = null,
        importedAt = Clock.System.now(),
    )
}
