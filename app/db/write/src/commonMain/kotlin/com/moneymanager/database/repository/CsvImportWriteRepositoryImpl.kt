@file:OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)

package com.moneymanager.database.repository

import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.database.csv.CsvTableManager
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.CsvImportId
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.domain.repository.CsvImportWriteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
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

                importId
            }
        }

    override suspend fun deleteImport(id: CsvImportId): Unit =
        withContext(coroutineContext) {
            val import =
                csvImportSelectQueries.selectImportById(id.id.toString()).executeAsOneOrNull()
                    ?: return@withContext

            // Drop the dynamic table first
            tableManager.dropCsvTable(import.table_name)

            // Delete column metadata (cascades from import delete, but be explicit)
            csvImportWriteQueries.deleteColumnsByImportId(id.id.toString())

            // Delete import metadata
            csvImportWriteQueries.deleteImport(id.id.toString())
        }

    override suspend fun updateRowTransferId(
        id: CsvImportId,
        rowIndex: Long,
        transferId: TransferId,
    ): Unit =
        withContext(coroutineContext) {
            val import =
                csvImportSelectQueries.selectImportById(id.id.toString()).executeAsOneOrNull()
                    ?: return@withContext

            tableManager.updateTransferId(import.table_name, rowIndex, transferId)
        }

    override suspend fun updateRowTransferIdsBatch(
        id: CsvImportId,
        rowTransferMap: Map<Long, TransferId>,
    ): Unit =
        withContext(coroutineContext) {
            if (rowTransferMap.isEmpty()) return@withContext

            val import =
                csvImportSelectQueries.selectImportById(id.id.toString()).executeAsOneOrNull()
                    ?: return@withContext

            tableManager.updateTransferIdsBatch(import.table_name, rowTransferMap)
        }

    override suspend fun updateRowStatus(
        id: CsvImportId,
        rowIndex: Long,
        status: String,
        transferId: TransferId?,
    ): Unit =
        withContext(coroutineContext) {
            val import =
                csvImportSelectQueries.selectImportById(id.id.toString()).executeAsOneOrNull()
                    ?: return@withContext

            tableManager.updateRowStatus(import.table_name, rowIndex, status, transferId)
        }

    override suspend fun updateRowStatusesBatch(
        id: CsvImportId,
        status: String,
        rowTransferMap: Map<Long, TransferId?>,
    ): Unit =
        withContext(coroutineContext) {
            if (rowTransferMap.isEmpty()) return@withContext

            val import =
                csvImportSelectQueries.selectImportById(id.id.toString()).executeAsOneOrNull()
                    ?: return@withContext

            database.transaction {
                rowTransferMap.forEach { (rowIndex, transferId) ->
                    tableManager.updateRowStatus(import.table_name, rowIndex, status, transferId)
                }
            }
        }

    override suspend fun resetRowStatuses(
        id: CsvImportId,
        rowIndexes: Collection<Long>,
    ): Unit =
        withContext(coroutineContext) {
            if (rowIndexes.isEmpty()) return@withContext

            val import =
                csvImportSelectQueries.selectImportById(id.id.toString()).executeAsOneOrNull()
                    ?: return@withContext

            tableManager.resetRowStatuses(import.table_name, rowIndexes)
        }

    override suspend fun saveError(
        id: CsvImportId,
        rowIndex: Long,
        errorMessage: String,
    ): Unit =
        withContext(coroutineContext) {
            csvImportWriteQueries.insertOrReplaceError(
                csv_import_id = id.id.toString(),
                row_index = rowIndex,
                error_message = errorMessage,
                error_timestamp = Clock.System.now().toEpochMilliseconds(),
            )
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
