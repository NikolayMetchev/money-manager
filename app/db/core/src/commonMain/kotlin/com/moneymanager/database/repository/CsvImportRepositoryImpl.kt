@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

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
import com.moneymanager.domain.repository.CsvImportRepository
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
    database: MoneyManagerDatabaseWrapper,
    private val coroutineContext: CoroutineContext = Dispatchers.Default,
) : CsvImportRepository {
    private val csvImportQueries = database.csvImportQueries
    private val tableManager = CsvTableManager(database)

    override suspend fun createImport(
        fileName: String,
        headers: List<String>,
        rows: List<List<String>>,
    ): CsvImportId =
        withContext(coroutineContext) {
            val importId = CsvImportId(Uuid.random())
            val tableName = "csv_import_${importId.id.toHexString().take(8)}"
            val columnCount = headers.size
            val timestamp = Clock.System.now()

            // Create the dynamic table
            tableManager.createCsvTable(tableName, columnCount)

            // Insert the data
            tableManager.insertRowsBatch(tableName, rows, columnCount)

            // Insert metadata
            csvImportQueries.insertImport(
                id = importId.id.toString(),
                tableName = tableName,
                originalFileName = fileName,
                importTimestamp = timestamp.toEpochMilliseconds(),
                rowCount = rows.size.toLong(),
                columnCount = columnCount.toLong(),
            )

            // Insert column metadata
            headers.forEachIndexed { index, header ->
                val columnId = Uuid.random()
                csvImportQueries.insertColumn(
                    id = columnId.toString(),
                    importId = importId.id.toString(),
                    columnIndex = index.toLong(),
                    originalName = header,
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
                                    columnIndex = col.columnIndex.toInt(),
                                    originalName = col.originalName,
                                )
                            }

                    CsvImport(
                        id = CsvImportId(Uuid.parse(import.id)),
                        tableName = import.tableName,
                        originalFileName = import.originalFileName,
                        importTimestamp = Instant.fromEpochMilliseconds(import.importTimestamp),
                        rowCount = import.rowCount.toInt(),
                        columnCount = import.columnCount.toInt(),
                        columns = columns,
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
                            columnIndex = col.columnIndex.toInt(),
                            originalName = col.originalName,
                        )
                    }

                CsvImport(
                    id = CsvImportId(Uuid.parse(it.id)),
                    tableName = it.tableName,
                    originalFileName = it.originalFileName,
                    importTimestamp = Instant.fromEpochMilliseconds(it.importTimestamp),
                    rowCount = it.rowCount.toInt(),
                    columnCount = it.columnCount.toInt(),
                    columns = columns,
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
                tableName = import.tableName,
                columnCount = import.columnCount.toInt(),
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
            tableManager.dropCsvTable(import.tableName)

            // Delete column metadata (cascades from import delete, but be explicit)
            csvImportQueries.deleteColumnsByImportId(id.id.toString())

            // Delete import metadata
            csvImportQueries.deleteImport(id.id.toString())
        }
}
