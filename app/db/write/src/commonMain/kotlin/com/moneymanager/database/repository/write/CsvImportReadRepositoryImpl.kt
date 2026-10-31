package com.moneymanager.database.repository.write

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.coroutines.throttleLatest
import com.moneymanager.database.csv.CsvTableManager
import com.moneymanager.database.repository.LISTING_REFRESH_PERIOD
import com.moneymanager.database.repository.createDeviceInfo
import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvColumnId
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csv.PdfImportSource
import com.moneymanager.domain.model.csv.PdfTextLine
import com.moneymanager.domain.model.csv.XlsxImportBlob
import com.moneymanager.domain.repository.CsvImportReadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.Instant
import kotlin.uuid.Uuid

class CsvImportReadRepositoryImpl(
    database: MoneyManagerDatabaseWrapper,
    private val coroutineContext: CoroutineContext = Dispatchers.Default,
) : CsvImportReadRepository {
    private val csvImportSelectQueries = database.csvImportSelectQueries
    private val entitySourceSelectQueries = database.entitySourceSelectQueries
    private val tableManager = CsvTableManager(database)

    override fun getAllImports(): Flow<List<CsvImport>> =
        csvImportSelectQueries
            .selectAllImports(::toCsvImportRecord)
            .asFlow()
            .throttleLatest(LISTING_REFRESH_PERIOD)
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
                csvImportSelectQueries.selectImportById(id.id.toString()).awaitAsOneOrNull()
                    ?: return@withContext emptyList()

            tableManager.queryRows(
                tableName = import.table_name,
                csvImportId = id.id.toString(),
                columnCount = import.column_count.toInt(),
                limit = limit,
                offset = offset,
            )
        }

    override suspend fun getAccountsCreatedByImport(id: CsvImportId): Set<AccountId> =
        withContext(coroutineContext) {
            entitySourceSelectQueries
                .selectAccountIdsCreatedByCsvImport(id.id.toString())
                .awaitAsList()
                .map { AccountId(it) }
                .toSet()
        }

    override suspend fun getTransferIdsCreatedByImport(id: CsvImportId): Set<TransferId> =
        withContext(coroutineContext) {
            entitySourceSelectQueries
                .selectTransferIdsCreatedByCsvImport(id.id.toString())
                .awaitAsList()
                .map { TransferId(it) }
                .toSet()
        }

    override suspend fun getStatementAccounts(ids: Collection<TransferId>): Map<TransferId, AccountId> =
        withContext(coroutineContext) {
            ids
                .map { it.id }
                .chunked(STATEMENT_ACCOUNT_CHUNK)
                .flatMap { chunk ->
                    entitySourceSelectQueries
                        .selectStatementAccountsOfTransfers(chunk)
                        .awaitAsList()
                        .map { TransferId(it.entity_id) to AccountId(requireNotNull(it.statement_account_id)) }
                }.toMap()
        }

    override suspend fun getTradeIdsCreatedByImport(id: CsvImportId): Set<TradeId> =
        withContext(coroutineContext) {
            entitySourceSelectQueries
                .selectTradeIdsCreatedByCsvImport(id.id.toString())
                .awaitAsList()
                .map { TradeId(it) }
                .toSet()
        }

    override suspend fun findImportsByChecksum(checksum: String): List<CsvImport> =
        withContext(coroutineContext) {
            csvImportSelectQueries.selectImportsByChecksum(checksum, ::toCsvImportRecord).awaitAsList().map { import ->
                toCsvImport(import)
            }
        }

    override suspend fun getXlsxBlob(id: CsvImportId): XlsxImportBlob? =
        withContext(coroutineContext) {
            csvImportSelectQueries.selectXlsxBlob(id.id.toString()).awaitAsOneOrNull()?.let { blob ->
                XlsxImportBlob(fileBytes = blob.file_bytes, worksheetName = blob.worksheet_name)
            }
        }

    override suspend fun getPdfSource(id: CsvImportId): PdfImportSource? =
        withContext(coroutineContext) {
            val importId = id.id.toString()
            csvImportSelectQueries.selectPdfBlob(importId).awaitAsOneOrNull()?.let { blob ->
                PdfImportSource(
                    fileBytes = blob.file_bytes,
                    lines =
                        csvImportSelectQueries
                            .selectPdfLines(importId) { page, line, text -> PdfTextLine(page.toInt(), line.toInt(), text) }
                            .awaitAsList(),
                    stagedRulesHash = blob.staged_rules_hash,
                )
            }
        }

    override suspend fun getPdfImportIds(): Set<CsvImportId> =
        withContext(coroutineContext) {
            csvImportSelectQueries.selectPdfImportIds().awaitAsList().mapTo(mutableSetOf()) { CsvImportId(Uuid.parse(it)) }
        }

    private data class CsvImportRecord(
        val importId: String,
        val tableName: String,
        val originalFileName: String,
        val importTimestampMs: Long,
        val rowCount: Int,
        val columnCount: Int,
        val platformName: String,
        val osName: String?,
        val machineName: String?,
        val deviceMake: String?,
        val deviceModel: String?,
        val fileChecksum: String,
        val fileLastModifiedMs: Long,
        val ignored: Boolean,
        val applicationCount: Int,
        val errorCount: Int,
        val lastAppliedStrategyId: String?,
        val lastAppliedStrategyName: String?,
        val lastAppliedAtMs: Long?,
        val lastAppliedAccountId: Long?,
        val lastAppliedCurrencyId: Long?,
        val lastUnimportedAtMs: Long?,
    )

    private fun toCsvImportRecord(
        importId: String,
        tableName: String,
        originalFileName: String,
        importTimestampMs: Long,
        rowCount: Long,
        columnCount: Long,
        fileChecksum: String,
        fileLastModifiedMs: Long,
        ignored: Long,
        applicationCount: Long,
        errorCount: Long,
        lastAppliedStrategyId: String?,
        lastAppliedStrategyName: String?,
        lastAppliedAtMs: Long?,
        lastAppliedAccountId: Long?,
        lastAppliedCurrencyId: Long?,
        lastUnimportedAtMs: Long?,
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
            platformName = platformName,
            osName = osName,
            machineName = machineName,
            deviceMake = deviceMake,
            deviceModel = deviceModel,
            fileChecksum = fileChecksum,
            fileLastModifiedMs = fileLastModifiedMs,
            ignored = ignored == 1L,
            applicationCount = applicationCount.toIntChecked("applicationCount"),
            errorCount = errorCount.toIntChecked("errorCount"),
            lastAppliedStrategyId = lastAppliedStrategyId,
            lastAppliedStrategyName = lastAppliedStrategyName,
            lastAppliedAtMs = lastAppliedAtMs,
            lastAppliedAccountId = lastAppliedAccountId,
            lastAppliedCurrencyId = lastAppliedCurrencyId,
            lastUnimportedAtMs = lastUnimportedAtMs,
        )

    private fun Long.toIntChecked(field: String): Int {
        require(this in Int.MIN_VALUE..Int.MAX_VALUE) { "$field out of Int range: $this" }
        return toInt()
    }

    private suspend fun toCsvImport(
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
            ignored = record.ignored,
            applicationCount = record.applicationCount,
            errorCount = record.errorCount,
            lastAppliedStrategyId = record.lastAppliedStrategyId,
            lastAppliedStrategyName = record.lastAppliedStrategyName,
            lastAppliedAtMs = record.lastAppliedAtMs,
            lastAppliedAccountId = record.lastAppliedAccountId,
            lastAppliedCurrencyId = record.lastAppliedCurrencyId,
            lastUnimportedAtMs = record.lastUnimportedAtMs,
            columns = columns,
        )

    private suspend fun toCsvImport(
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
        ignored: Boolean,
        applicationCount: Int,
        errorCount: Int,
        lastAppliedStrategyId: String?,
        lastAppliedStrategyName: String?,
        lastAppliedAtMs: Long?,
        lastAppliedAccountId: Long?,
        lastAppliedCurrencyId: Long?,
        lastUnimportedAtMs: Long?,
        columns: List<CsvColumn>? = null,
    ): CsvImport =
        CsvImport(
            id = CsvImportId(Uuid.parse(importId)),
            tableName = tableName,
            originalFileName = originalFileName,
            importTimestamp = Instant.fromEpochMilliseconds(importTimestampMs),
            rowCount = rowCount,
            columnCount = columnCount,
            columns = columns ?: loadColumns(importId),
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
            ignored = ignored,
            applicationCount = applicationCount,
            errorCount = errorCount,
            lastAppliedStrategyId =
                lastAppliedStrategyId?.let { strategyId ->
                    CsvImportStrategyId(Uuid.parse(strategyId))
                },
            lastAppliedStrategyName = lastAppliedStrategyName,
            lastAppliedAt =
                lastAppliedAtMs?.let { appliedAt ->
                    Instant.fromEpochMilliseconds(appliedAt)
                },
            lastAppliedAccountId = lastAppliedAccountId?.let(::AccountId),
            lastAppliedCurrencyId = lastAppliedCurrencyId?.let(::CurrencyId),
            lastUnimportedAt = lastUnimportedAtMs?.let(Instant::fromEpochMilliseconds),
        )

    private suspend fun loadColumns(importId: String): List<CsvColumn> =
        csvImportSelectQueries
            .selectColumnsByImportId(importId)
            .awaitAsList()
            .map { col ->
                CsvColumn(
                    id = CsvColumnId(Uuid.parse(col.id)),
                    columnIndex = col.column_index.toInt(),
                    originalName = col.original_name,
                )
            }
}

/** SQLite binds at most 999 variables; statement-account lookups bind one per transfer id. */
private const val STATEMENT_ACCOUNT_CHUNK = 500
