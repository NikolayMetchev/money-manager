@file:OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)

package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.database.csv.CsvTableManager
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvColumnId
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csv.CsvImportId
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import com.moneymanager.domain.repository.CsvImportReadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class CsvImportReadRepositoryImpl(
    database: MoneyManagerDatabaseWrapper,
    private val coroutineContext: CoroutineContext = Dispatchers.Default,
) : CsvImportReadRepository {
    private val csvImportSelectQueries = database.csvImportSelectQueries
    private val tableManager = CsvTableManager(database)

    override fun getAllImports(): Flow<List<CsvImport>> =
        csvImportSelectQueries
            .selectAllImports(::toCsvImportRecord)
            .asFlow()
            .mapToList(coroutineContext)
            .map { imports ->
                imports.map { import -> toCsvImport(import) }
            }

    override fun getImport(id: CsvImportId): Flow<CsvImport?> {
        val importFlow =
            csvImportSelectQueries
                .selectImportById(id.id.toString(), ::toCsvImportRecord)
                .asFlow()
                .mapToOneOrNull(coroutineContext)

        val columnsFlow =
            csvImportSelectQueries
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
                csvImportSelectQueries.selectImportById(id.id.toString()).executeAsOneOrNull()
                    ?: return@withContext emptyList()

            tableManager.queryRows(
                tableName = import.table_name,
                csvImportId = id.id.toString(),
                columnCount = import.column_count.toInt(),
                limit = limit,
                offset = offset,
            )
        }

    override suspend fun findImportsByChecksum(checksum: String): List<CsvImport> =
        withContext(coroutineContext) {
            csvImportSelectQueries.selectImportsByChecksum(checksum, ::toCsvImportRecord).executeAsList().map { import ->
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
            rowCount = rowCount.toIntChecked("rowCount"),
            columnCount = columnCount.toIntChecked("columnCount"),
            deviceId = deviceId,
            platformName = platformName,
            osName = osName,
            machineName = machineName,
            deviceMake = deviceMake,
            deviceModel = deviceModel,
            fileChecksum = fileChecksum,
            fileLastModifiedMs = fileLastModifiedMs,
            applicationCount = applicationCount.toIntChecked("applicationCount"),
            lastAppliedStrategyId = lastAppliedStrategyId,
            lastAppliedStrategyName = lastAppliedStrategyName,
            lastAppliedAtMs = lastAppliedAtMs,
        )

    private fun Long.toIntChecked(field: String): Int {
        require(this in Int.MIN_VALUE..Int.MAX_VALUE) { "$field out of Int range: $this" }
        return toInt()
    }

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
                createDeviceInfo(
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
        csvImportSelectQueries
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
