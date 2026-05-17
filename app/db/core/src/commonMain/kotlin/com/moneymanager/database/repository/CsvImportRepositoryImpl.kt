@file:OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)

package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.database.csv.CsvTableManager
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvColumnId
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csv.CsvImportId
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import com.moneymanager.domain.repository.CsvImportRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class CsvImportRepositoryImpl(
    private val database: MoneyManagerDatabaseWrapper,
    private val deviceId: DeviceId,
    private val coroutineContext: CoroutineContext = Dispatchers.Default,
) : CsvImportRepository {
    private val csvImportQueries = database.csvImportQueries
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

                csvImportQueries.insertImport(
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
                    csvImportQueries.insertColumn(
                        id = columnId.toString(),
                        import_id = importId.id.toString(),
                        column_index = index.toLong(),
                        original_name = header,
                    )
                }

                importId
            }
        }

    override fun getAllImports(): Flow<List<CsvImport>> =
        csvImportQueries
            .selectAllImports(::toCsvImportRecord)
            .asFlow()
            .mapToList(coroutineContext)
            .map { imports ->
                imports.map { import -> toCsvImport(import) }
            }

    override fun getImport(id: CsvImportId): Flow<CsvImport?> {
        val importFlow =
            csvImportQueries
                .selectImportById(id.id.toString(), ::toCsvImportRecord)
                .asFlow()
                .mapToOneOrNull(coroutineContext)

        val columnsFlow =
            csvImportQueries
                .selectColumnsByImportId(id.id.toString())
                .asFlow()
                .mapToList(coroutineContext)

        return combine(importFlow, columnsFlow) { import, columnEntities ->
            import?.let {
                toCsvImport(
                    record = it,
                    columns =
                        columnEntities.map { col ->
                            CsvColumn(
                                id = CsvColumnId(Uuid.parse(col.id)),
                                columnIndex = col.column_index.toInt(),
                                originalName = col.original_name,
                            )
                        },
                )
            }
        }
    }

    override suspend fun getImportRows(
        id: CsvImportId,
        limit: Int,
        offset: Int,
    ): List<CsvRow> =
        withContext(coroutineContext) {
            val import =
                csvImportQueries.selectImportById(id.id.toString()).executeAsOneOrNull()
                    ?: return@withContext emptyList()

            tableManager.queryRows(
                tableName = import.table_name,
                csvImportId = id.id.toString(),
                columnCount = import.column_count.toInt(),
                limit = limit,
                offset = offset,
            )
        }

    override suspend fun deleteImport(id: CsvImportId): Unit =
        withContext(coroutineContext) {
            val import =
                csvImportQueries.selectImportById(id.id.toString()).executeAsOneOrNull()
                    ?: return@withContext

            // Drop the dynamic table first
            tableManager.dropCsvTable(import.table_name)

            // Delete column metadata (cascades from import delete, but be explicit)
            csvImportQueries.deleteColumnsByImportId(id.id.toString())

            // Delete import metadata
            csvImportQueries.deleteImport(id.id.toString())
        }

    override suspend fun updateRowTransferId(
        id: CsvImportId,
        rowIndex: Long,
        transferId: TransferId,
    ): Unit =
        withContext(coroutineContext) {
            val import =
                csvImportQueries.selectImportById(id.id.toString()).executeAsOneOrNull()
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
                csvImportQueries.selectImportById(id.id.toString()).executeAsOneOrNull()
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
                csvImportQueries.selectImportById(id.id.toString()).executeAsOneOrNull()
                    ?: return@withContext

            tableManager.updateRowStatus(import.table_name, rowIndex, status, transferId)
        }

    override suspend fun saveError(
        id: CsvImportId,
        rowIndex: Long,
        errorMessage: String,
    ): Unit =
        withContext(coroutineContext) {
            csvImportQueries.insertOrReplaceError(
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
            csvImportQueries.deleteError(
                csv_import_id = id.id.toString(),
                row_index = rowIndex,
            )
        }

    override suspend fun recordImportApplication(
        id: CsvImportId,
        strategyId: CsvImportStrategyId,
        strategyName: String,
        appliedAt: Instant,
    ): Unit =
        withContext(coroutineContext) {
            csvImportQueries.insertApplication(
                id = Uuid.random().toString(),
                csv_import_id = id.id.toString(),
                strategy_id = strategyId.id.toString(),
                strategy_name = strategyName,
                applied_at = appliedAt.toEpochMilliseconds(),
            )
        }

    override suspend fun findImportsByChecksum(checksum: String): List<CsvImport> =
        withContext(coroutineContext) {
            csvImportQueries.selectImportsByChecksum(checksum, ::toCsvImportRecord).executeAsList().map { import ->
                toCsvImport(import)
            }
        }

    private data class CsvImportRecord(
        val importId: String,
        val tableName: String,
        val originalFileName: String,
        val importTimestampMs: Long,
        val rowCount: Int,
        val columnCount: Int,
        val deviceId: Long,
        val platformName: String,
        val osName: String?,
        val machineName: String?,
        val deviceMake: String?,
        val deviceModel: String?,
        val fileChecksum: String,
        val fileLastModifiedMs: Long,
        val applicationCount: Int,
        val lastAppliedStrategyId: String?,
        val lastAppliedStrategyName: String?,
        val lastAppliedAtMs: Long?,
    )

    private fun toCsvImportRecord(
        importId: String,
        tableName: String,
        originalFileName: String,
        importTimestampMs: Long,
        rowCount: Long,
        columnCount: Long,
        deviceId: Long,
        fileChecksum: String,
        fileLastModifiedMs: Long,
        applicationCount: Long,
        lastAppliedStrategyId: String?,
        lastAppliedStrategyName: String?,
        lastAppliedAtMs: Long?,
        platformName: String,
        osName: String?,
        machineName: String?,
        deviceMake: String?,
        deviceModel: String?,
    ): CsvImportRecord =
        CsvImportRecord(
            importId = importId,
            tableName = tableName,
            originalFileName = originalFileName,
            importTimestampMs = importTimestampMs,
            rowCount = rowCount.toInt(),
            columnCount = columnCount.toInt(),
            deviceId = deviceId,
            platformName = platformName,
            osName = osName,
            machineName = machineName,
            deviceMake = deviceMake,
            deviceModel = deviceModel,
            fileChecksum = fileChecksum,
            fileLastModifiedMs = fileLastModifiedMs,
            applicationCount = applicationCount.toInt(),
            lastAppliedStrategyId = lastAppliedStrategyId,
            lastAppliedStrategyName = lastAppliedStrategyName,
            lastAppliedAtMs = lastAppliedAtMs,
        )

    private fun toCsvImport(
        record: CsvImportRecord,
        columns: List<CsvColumn> = emptyList(),
    ): CsvImport =
        toCsvImport(
            importId = record.importId,
            tableName = record.tableName,
            originalFileName = record.originalFileName,
            importTimestampMs = record.importTimestampMs,
            rowCount = record.rowCount,
            columnCount = record.columnCount,
            platformName = record.platformName,
            osName = record.osName,
            machineName = record.machineName,
            deviceMake = record.deviceMake,
            deviceModel = record.deviceModel,
            fileChecksum = record.fileChecksum,
            fileLastModifiedMs = record.fileLastModifiedMs,
            applicationCount = record.applicationCount,
            lastAppliedStrategyId = record.lastAppliedStrategyId,
            lastAppliedStrategyName = record.lastAppliedStrategyName,
            lastAppliedAtMs = record.lastAppliedAtMs,
            columns = columns,
        )

    private fun toCsvImport(
        importId: String,
        tableName: String,
        originalFileName: String,
        importTimestampMs: Long,
        rowCount: Int,
        columnCount: Int,
        platformName: String,
        osName: String?,
        machineName: String?,
        deviceMake: String?,
        deviceModel: String?,
        fileChecksum: String,
        fileLastModifiedMs: Long,
        applicationCount: Int,
        lastAppliedStrategyId: String?,
        lastAppliedStrategyName: String?,
        lastAppliedAtMs: Long?,
        columns: List<CsvColumn> = loadColumns(importId),
    ): CsvImport =
        CsvImport(
            id = CsvImportId(Uuid.parse(importId)),
            tableName = tableName,
            originalFileName = originalFileName,
            importTimestamp = Instant.fromEpochMilliseconds(importTimestampMs),
            rowCount = rowCount,
            columnCount = columnCount,
            columns = columns,
            deviceInfo =
                DeviceRepositoryImpl.createDeviceInfo(
                    platformName = platformName,
                    osName = osName,
                    machineName = machineName,
                    deviceMake = deviceMake,
                    deviceModel = deviceModel,
                ),
            fileChecksum = fileChecksum,
            fileLastModified = Instant.fromEpochMilliseconds(fileLastModifiedMs),
            applicationCount = applicationCount,
            lastAppliedStrategyId =
                lastAppliedStrategyId?.let { strategyId ->
                    CsvImportStrategyId(Uuid.parse(strategyId))
                },
            lastAppliedStrategyName = lastAppliedStrategyName,
            lastAppliedAt =
                lastAppliedAtMs?.let { appliedAt ->
                    Instant.fromEpochMilliseconds(appliedAt)
                },
        )

    private fun loadColumns(importId: String): List<CsvColumn> =
        csvImportQueries
            .selectColumnsByImportId(importId)
            .executeAsList()
            .map { col ->
                CsvColumn(
                    id = CsvColumnId(Uuid.parse(col.id)),
                    columnIndex = col.column_index.toInt(),
                    originalName = col.original_name,
                )
            }
}
