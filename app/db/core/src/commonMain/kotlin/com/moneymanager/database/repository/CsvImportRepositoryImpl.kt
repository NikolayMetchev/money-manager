@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.database.csv.CsvTableManager
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvColumnId
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csv.CsvImportId
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.repository.CsvImportRepository
import com.moneymanager.domain.repository.DeviceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

class CsvImportRepositoryImpl(
    private val database: MoneyManagerDatabaseWrapper,
    private val deviceRepository: DeviceRepository,
    private val coroutineContext: CoroutineContext = Dispatchers.Default,
) : CsvImportRepository {
    private val csvImportQueries = database.csvImportQueries
    private val tableManager = CsvTableManager(database)

    override suspend fun createImport(
        fileName: String,
        headers: List<String>,
        rows: List<List<String>>,
        deviceInfo: DeviceInfo,
    ): CsvImportId =
        withContext(coroutineContext) {
            val importId = CsvImportId(Uuid.random())
            val tableName = "csv_import_${importId.id.toHexString().take(8)}"
            val columnCount = headers.size
            val timestamp = Clock.System.now()
            val deviceId = deviceRepository.getOrCreateDevice(deviceInfo)

            // Create the dynamic table
            tableManager.createCsvTable(tableName, columnCount)

            // Insert the data
            tableManager.insertRowsBatch(tableName, rows, columnCount)

            // Insert metadata
            csvImportQueries.insertImport(
                id = importId.id.toString(),
                table_name = tableName,
                original_file_name = fileName,
                import_timestamp = timestamp.toEpochMilliseconds(),
                row_count = rows.size.toLong(),
                column_count = columnCount.toLong(),
                device_id = deviceId,
            )

            // Insert column metadata
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

    override fun getAllImports(): Flow<List<CsvImport>> {
        return csvImportQueries.selectAllImports()
            .asFlow()
            .mapToList(coroutineContext)
            .map { imports ->
                imports.map { import ->
                    val columns =
                        csvImportQueries.selectColumnsByImportId(import.id)
                            .executeAsList()
                            .map { col ->
                                CsvColumn(
                                    id = CsvColumnId(Uuid.parse(col.id)),
                                    columnIndex = col.column_index.toInt(),
                                    originalName = col.original_name,
                                )
                            }

                    CsvImport(
                        id = CsvImportId(Uuid.parse(import.id)),
                        tableName = import.table_name,
                        originalFileName = import.original_file_name,
                        importTimestamp = Instant.fromEpochMilliseconds(import.import_timestamp),
                        rowCount = import.row_count.toInt(),
                        columnCount = import.column_count.toInt(),
                        columns = columns,
                        deviceInfo =
                            DeviceRepositoryImpl.createDeviceInfo(
                                platformName = import.platform_name,
                                osName = import.os_name,
                                machineName = import.machine_name,
                                deviceMake = import.device_make,
                                deviceModel = import.device_model,
                            ),
                    )
                }
            }
    }

    override fun getImport(id: CsvImportId): Flow<CsvImport?> {
        val importFlow =
            csvImportQueries.selectImportById(id.id.toString())
                .asFlow()
                .mapToOneOrNull(coroutineContext)

        val columnsFlow =
            csvImportQueries.selectColumnsByImportId(id.id.toString())
                .asFlow()
                .mapToList(coroutineContext)

        return combine(importFlow, columnsFlow) { import, columnEntities ->
            import?.let {
                val columns =
                    columnEntities.map { col ->
                        CsvColumn(
                            id = CsvColumnId(Uuid.parse(col.id)),
                            columnIndex = col.column_index.toInt(),
                            originalName = col.original_name,
                        )
                    }

                CsvImport(
                    id = CsvImportId(Uuid.parse(it.id)),
                    tableName = it.table_name,
                    originalFileName = it.original_file_name,
                    importTimestamp = Instant.fromEpochMilliseconds(it.import_timestamp),
                    rowCount = it.row_count.toInt(),
                    columnCount = it.column_count.toInt(),
                    columns = columns,
                    deviceInfo =
                        DeviceRepositoryImpl.createDeviceInfo(
                            platformName = it.platform_name,
                            osName = it.os_name,
                            machineName = it.machine_name,
                            deviceMake = it.device_make,
                            deviceModel = it.device_model,
                        ),
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
}
