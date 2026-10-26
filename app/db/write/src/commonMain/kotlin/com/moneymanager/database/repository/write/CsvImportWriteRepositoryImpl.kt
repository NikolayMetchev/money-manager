package com.moneymanager.database.repository.write

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.moneymanager.database.csv.CsvTableManager
import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.database.write.forgetMatchedSources
import com.moneymanager.database.write.recordMatchedSource
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.CsvRowLink
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.csv.PdfImportSource
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.domain.repository.write.CsvImportWriteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

class CsvImportWriteRepositoryImpl(
    private val database: MoneyManagerDatabaseWrapper,
    private val deviceId: DeviceId,
    reader: CsvImportReadRepository,
    private val coroutineContext: CoroutineContext = Dispatchers.Default,
) : CsvImportWriteRepository,
    CsvImportReadRepository by reader {
    private val csvImportSelectQueries = database.csvImportSelectQueries
    private val csvImportWriteQueries = database.csvImportWriteQueries
    private val tableManager = CsvTableManager(database)

    override suspend fun createImport(
        fileName: String,
        headers: List<String>,
        rows: List<List<String>>,
        fileChecksum: String,
        fileLastModified: Instant,
        xlsxBytes: ByteArray?,
        xlsxWorksheetName: String?,
        pdf: PdfImportSource?,
    ): CsvImportId =
        withContext(coroutineContext) {
            database.transactionWithResult {
                val importId = CsvImportId(Uuid.random())
                val tableName = "csv_import_${importId.id.toHexString().take(8)}"
                val columnCount = headers.size
                val timestamp = Clock.System.now()

                tableManager.createCsvTable(tableName, columnCount)
                tableManager.insertRowsBatch(tableName, rows, columnCount)

                csvImportWriteQueries.insertImport(
                    id = importId.id.toString(),
                    table_name = tableName,
                    original_file_name = fileName,
                    import_timestamp = timestamp.toEpochMilliseconds(),
                    row_count = rows.size.toLong(),
                    column_count = columnCount.toLong(),
                    device_id = deviceId.id,
                    file_checksum = fileChecksum,
                    file_last_modified = fileLastModified.toEpochMilliseconds(),
                )

                headers.forEachIndexed { index, header ->
                    val columnId = Uuid.random()
                    csvImportWriteQueries.insertColumn(
                        id = columnId.toString(),
                        import_id = importId.id.toString(),
                        column_index = index.toLong(),
                        original_name = header,
                    )
                }

                if (xlsxBytes != null && xlsxWorksheetName != null) {
                    csvImportWriteQueries.insertXlsxBlob(
                        csv_import_id = importId.id.toString(),
                        file_bytes = xlsxBytes,
                        worksheet_name = xlsxWorksheetName,
                    )
                }
                pdf?.let { storePdf(importId.id.toString(), it) }

                importId
            }
        }

    override suspend fun restageImport(
        id: CsvImportId,
        headers: List<String>,
        rows: List<List<String>>,
        worksheetName: String?,
        pdfRulesHash: String?,
    ): Unit =
        withContext(coroutineContext) {
            database.transaction {
                val import =
                    csvImportSelectQueries.selectImportById(id.id.toString()).awaitAsOneOrNull()
                        ?: return@transaction
                val columnCount = headers.size

                // Recreate the dynamic table with the new column shape and rows. The row_index sequence
                // restarts, so any previously written per-row errors are stale — clear them.
                // The new rows carry no links, so neither may their matched entries.
                database.forgetMatchedSources(Source.Csv(id), records = null)
                tableManager.dropCsvTable(import.table_name)
                tableManager.createCsvTable(import.table_name, columnCount)
                tableManager.insertRowsBatch(import.table_name, rows, columnCount)

                csvImportWriteQueries.deleteColumnsByImportId(id.id.toString())
                headers.forEachIndexed { index, header ->
                    csvImportWriteQueries.insertColumn(
                        id = Uuid.random().toString(),
                        import_id = id.id.toString(),
                        column_index = index.toLong(),
                        original_name = header,
                    )
                }

                csvImportWriteQueries.deleteErrorsByImportId(id.id.toString())
                csvImportWriteQueries.updateImportCounts(
                    row_count = rows.size.toLong(),
                    column_count = columnCount.toLong(),
                    id = id.id.toString(),
                )
                worksheetName?.let {
                    csvImportWriteQueries.updateXlsxBlobWorksheet(worksheet_name = it, csv_import_id = id.id.toString())
                }
                csvImportWriteQueries.updatePdfStagedRulesHash(pdfRulesHash, id.id.toString())
            }
        }

    override suspend fun repopulateImport(
        id: CsvImportId,
        headers: List<String>,
        rows: List<List<String>>,
        fileChecksum: String,
        fileLastModified: Instant,
        carriedRows: List<CsvRowLink>,
        rowIndexRemap: Map<Long, Long>,
        xlsxBytes: ByteArray?,
        pdf: PdfImportSource?,
    ): Unit =
        withContext(coroutineContext) {
            database.transaction {
                val import =
                    csvImportSelectQueries.selectImportById(id.id.toString()).awaitAsOneOrNull()
                        ?: return@transaction
                val importId = id.id.toString()

                // Dropping an AUTOINCREMENT table also drops its sequence, so the new rows number from 1 in
                // order — the indexes the caller computed carriedRows and rowIndexRemap against.
                tableManager.dropCsvTable(import.table_name)
                tableManager.createCsvTable(import.table_name, headers.size)
                tableManager.insertRowsBatch(import.table_name, rows, headers.size)
                carriedRows.forEach { link ->
                    tableManager.updateRowStatus(import.table_name, link.rowIndex, link.status.name, link.transferId)
                }

                csvImportWriteQueries.deleteColumnsByImportId(importId)
                headers.forEachIndexed { index, header ->
                    csvImportWriteQueries.insertColumn(
                        id = Uuid.random().toString(),
                        import_id = importId,
                        column_index = index.toLong(),
                        original_name = header,
                    )
                }
                csvImportWriteQueries.updateImportCounts(
                    row_count = rows.size.toLong(),
                    column_count = headers.size.toLong(),
                    id = importId,
                )
                csvImportWriteQueries.updateImportFile(fileChecksum, fileLastModified.toEpochMilliseconds(), importId)
                csvImportWriteQueries.deleteErrorsByImportId(importId)

                csvImportWriteQueries.parkEntitySourceRows(importId)
                rowIndexRemap.forEach { (oldIndex, newIndex) ->
                    csvImportWriteQueries.moveEntitySourceRow(newIndex = newIndex, importId = importId, oldIndex = oldIndex)
                }
                csvImportWriteQueries.clearParkedEntitySourceRows(importId)

                xlsxBytes?.let { csvImportWriteQueries.updateXlsxBlobBytes(it, importId) }
                pdf?.let { storePdf(importId, it) }
            }
        }

    private suspend fun storePdf(
        importId: String,
        pdf: PdfImportSource,
    ) {
        csvImportWriteQueries.insertOrReplacePdfBlob(importId, pdf.fileBytes)
        csvImportWriteQueries.updatePdfStagedRulesHash(pdf.stagedRulesHash, importId)
        csvImportWriteQueries.deletePdfLines(importId)
        pdf.lines.forEach { line ->
            csvImportWriteQueries.insertPdfLine(importId, line.page.toLong(), line.line.toLong(), line.text)
        }
    }

    override suspend fun deleteImport(id: CsvImportId): Unit =
        withContext(coroutineContext) {
            val import =
                csvImportSelectQueries.selectImportById(id.id.toString()).awaitAsOneOrNull()
                    ?: return@withContext

            database.transaction {
                // A file staged from an import folder references this import; forget the file too, so the
                // delete isn't blocked and the next scan of that folder stages the file again.
                csvImportWriteQueries.deleteDirectoryFilesForImport(id.id.toString())
                // Its rows' links go with it; their matched entries would otherwise block the delete.
                database.forgetMatchedSources(Source.Csv(id), records = null)

                tableManager.dropCsvTable(import.table_name)

                // Delete column metadata (cascades from import delete, but be explicit)
                csvImportWriteQueries.deleteColumnsByImportId(id.id.toString())

                csvImportWriteQueries.deleteImport(id.id.toString())
            }
        }

    override suspend fun resetToUnimported(id: CsvImportId): Unit =
        withContext(coroutineContext) {
            val import =
                csvImportSelectQueries.selectImportById(id.id.toString()).awaitAsOneOrNull()
                    ?: return@withContext

            database.transaction {
                database.forgetMatchedSources(Source.Csv(id), records = null)
                tableManager.resetAllRowStatuses(import.table_name)
                csvImportWriteQueries.deleteErrorsByImportId(id.id.toString())
                csvImportWriteQueries.deleteApplicationsByImportId(id.id.toString())
            }
        }

    override suspend fun setImportIgnored(
        id: CsvImportId,
        ignored: Boolean,
    ): Unit =
        withContext(coroutineContext) {
            csvImportWriteQueries.setImportIgnored(if (ignored) 1L else 0L, id.id.toString())
        }

    override suspend fun updateRowStatusesBatch(
        id: CsvImportId,
        status: String,
        rowTransferMap: Map<Long, TransferId?>,
    ): Unit =
        withContext(coroutineContext) {
            if (rowTransferMap.isEmpty()) return@withContext

            val import =
                csvImportSelectQueries.selectImportById(id.id.toString()).awaitAsOneOrNull()
                    ?: return@withContext

            database.transaction {
                database.forgetMatchedSources(Source.Csv(id), rowTransferMap.keys)
                rowTransferMap.forEach { (rowIndex, transferId) ->
                    tableManager.updateRowStatus(import.table_name, rowIndex, status, transferId)
                    if (transferId != null && status != ImportStatus.IMPORTED.name) {
                        database.recordMatchedSource(deviceId, Source.Csv(id, rowIndex), transferId.id)
                    }
                }
            }
        }

    override suspend fun resetRowStatuses(
        id: CsvImportId,
        rowIndexes: Collection<Long>?,
    ): Unit =
        withContext(coroutineContext) {
            if (rowIndexes?.isEmpty() == true) return@withContext

            val import =
                csvImportSelectQueries.selectImportById(id.id.toString()).awaitAsOneOrNull()
                    ?: return@withContext

            database.transaction {
                database.forgetMatchedSources(Source.Csv(id), rowIndexes)
                if (rowIndexes == null) {
                    tableManager.resetAllRowStatuses(import.table_name)
                } else {
                    tableManager.resetRowStatuses(import.table_name, rowIndexes)
                }
            }
        }

    override suspend fun saveErrors(
        id: CsvImportId,
        errors: Map<Long, String>,
    ): Unit =
        withContext(coroutineContext) {
            if (errors.isEmpty()) return@withContext
            val timestamp = Clock.System.now().toEpochMilliseconds()
            database.transaction {
                errors.forEach { (rowIndex, errorMessage) ->
                    csvImportWriteQueries.insertOrReplaceError(
                        csv_import_id = id.id.toString(),
                        row_index = rowIndex,
                        error_message = errorMessage,
                        error_timestamp = timestamp,
                    )
                }
            }
        }

    override suspend fun clearError(
        id: CsvImportId,
        rowIndex: Long,
    ): Unit =
        withContext(coroutineContext) {
            csvImportWriteQueries.deleteError(
                csv_import_id = id.id.toString(),
                row_index = rowIndex,
            )
        }

    override suspend fun clearErrors(
        id: CsvImportId,
        rowIndexes: Collection<Long>,
    ): Unit =
        withContext(coroutineContext) {
            if (rowIndexes.isEmpty()) return@withContext

            database.transaction {
                rowIndexes.forEach { rowIndex ->
                    csvImportWriteQueries.deleteError(
                        csv_import_id = id.id.toString(),
                        row_index = rowIndex,
                    )
                }
            }
        }

    override suspend fun recordImportApplication(
        id: CsvImportId,
        strategyId: CsvImportStrategyId,
        strategyName: String,
        appliedAt: Instant,
    ): Unit =
        withContext(coroutineContext) {
            csvImportWriteQueries.insertApplication(
                id = Uuid.random().toString(),
                csv_import_id = id.id.toString(),
                strategy_id = strategyId.id.toString(),
                strategy_name = strategyName,
                applied_at = appliedAt.toEpochMilliseconds(),
            )
        }
}
