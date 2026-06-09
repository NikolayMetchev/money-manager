@file:OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)

package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.database.sql.Qif_record
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import com.moneymanager.domain.model.qif.QifImport
import com.moneymanager.domain.model.qif.QifImportId
import com.moneymanager.domain.model.qif.QifImportRecord
import com.moneymanager.domain.model.qif.QifRecordSplit
import com.moneymanager.domain.repository.QifImportRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class QifImportRepositoryImpl(
    private val database: MoneyManagerDatabaseWrapper,
    private val deviceId: DeviceId,
    private val coroutineContext: CoroutineContext = Dispatchers.Default,
) : QifImportRepository {
    private val queries = database.qifImportQueries

    override suspend fun createImport(
        fileName: String,
        records: List<QifImportRecord>,
        accountType: String,
        fileChecksum: String,
        fileLastModified: Instant,
    ): QifImportId =
        withContext(coroutineContext) {
            database.transactionWithResult {
                val importId = QifImportId(Uuid.random())
                val importIdString = importId.id.toString()
                val timestamp = Clock.System.now()
                val unsupportedCount = records.count { !it.supported }

                queries.insertImport(
                    id = importIdString,
                    original_file_name = fileName,
                    import_timestamp = timestamp.toEpochMilliseconds(),
                    record_count = records.size.toLong(),
                    unsupported_count = unsupportedCount.toLong(),
                    account_type = accountType,
                    device_id = deviceId.id,
                    file_checksum = fileChecksum,
                    file_last_modified = fileLastModified.toEpochMilliseconds(),
                )

                records.forEach { record ->
                    queries.insertRecord(
                        import_id = importIdString,
                        record_index = record.recordIndex,
                        section_type = record.sectionType,
                        account_name = record.accountName,
                        supported = if (record.supported) 1L else 0L,
                        raw_text = record.rawText,
                        field_date = record.date,
                        field_amount = record.amount,
                        field_payee = record.payee,
                        field_memo = record.memo,
                        field_category = record.category,
                        field_transfer_account = record.transferAccount,
                        field_check_number = record.checkNumber,
                        field_cleared = record.clearedStatus,
                        splits_json = record.splits.takeIf { it.isNotEmpty() }?.let { Json.encodeToString(it) },
                    )
                }

                importId
            }
        }

    override fun getAllImports(): Flow<List<QifImport>> =
        queries
            .selectAllImports(::toQifImport)
            .asFlow()
            .mapToList(coroutineContext)

    override fun getImport(id: QifImportId): Flow<QifImport?> =
        queries
            .selectImportById(id.id.toString(), ::toQifImport)
            .asFlow()
            .mapToOneOrNull(coroutineContext)
            .map { it }

    override suspend fun getImportRecords(
        id: QifImportId,
        limit: Int,
        offset: Int,
    ): List<QifImportRecord> =
        withContext(coroutineContext) {
            queries
                .selectRecordsByImportId(id.id.toString(), limit.toLong(), offset.toLong())
                .executeAsList()
                .map { it.toDomain() }
        }

    override suspend fun countRecords(id: QifImportId): Int =
        withContext(coroutineContext) {
            queries.countRecords(id.id.toString()).executeAsOne().toInt()
        }

    override suspend fun deleteImport(id: QifImportId): Unit =
        withContext(coroutineContext) {
            queries.deleteImport(id.id.toString())
        }

    override suspend fun updateRecordStatusesBatch(
        id: QifImportId,
        status: String,
        recordTransferMap: Map<Long, TransferId?>,
    ): Unit =
        withContext(coroutineContext) {
            if (recordTransferMap.isEmpty()) return@withContext
            val importIdString = id.id.toString()
            database.transaction {
                recordTransferMap.forEach { (recordIndex, transferId) ->
                    queries.updateRecordStatus(
                        import_status = status,
                        transaction_id = transferId?.id?.toString(),
                        import_id = importIdString,
                        record_index = recordIndex,
                    )
                }
            }
        }

    override suspend fun saveError(
        id: QifImportId,
        recordIndex: Long,
        errorMessage: String,
    ): Unit =
        withContext(coroutineContext) {
            queries.insertOrReplaceError(
                qif_import_id = id.id.toString(),
                record_index = recordIndex,
                error_message = errorMessage,
                error_timestamp = Clock.System.now().toEpochMilliseconds(),
            )
        }

    override suspend fun clearErrors(
        id: QifImportId,
        recordIndexes: Collection<Long>,
    ): Unit =
        withContext(coroutineContext) {
            if (recordIndexes.isEmpty()) return@withContext
            val importIdString = id.id.toString()
            database.transaction {
                recordIndexes.forEach { recordIndex ->
                    queries.deleteError(qif_import_id = importIdString, record_index = recordIndex)
                }
            }
        }

    override suspend fun recordImportApplication(
        id: QifImportId,
        strategyId: CsvImportStrategyId,
        strategyName: String,
        appliedAt: Instant,
    ): Unit =
        withContext(coroutineContext) {
            queries.insertApplication(
                id = Uuid.random().toString(),
                qif_import_id = id.id.toString(),
                strategy_id = strategyId.id.toString(),
                strategy_name = strategyName,
                applied_at = appliedAt.toEpochMilliseconds(),
            )
        }

    override suspend fun findImportsByChecksum(checksum: String): List<QifImport> =
        withContext(coroutineContext) {
            queries.selectImportsByChecksum(checksum, ::toQifImport).executeAsList()
        }

    private fun Qif_record.toDomain(): QifImportRecord =
        QifImportRecord(
            recordIndex = record_index,
            sectionType = section_type,
            accountName = account_name,
            supported = supported != 0L,
            rawText = raw_text,
            date = field_date,
            amount = field_amount,
            payee = field_payee,
            memo = field_memo,
            category = field_category,
            transferAccount = field_transfer_account,
            checkNumber = field_check_number,
            clearedStatus = field_cleared,
            splits = splits_json?.let { Json.decodeFromString<List<QifRecordSplit>>(it) }.orEmpty(),
            transferId = transaction_id?.toLongOrNull()?.let { TransferId(it) },
            importStatus = import_status?.let { ImportStatus.valueOf(it) },
        )

    @Suppress("LongParameterList", "UnusedParameter")
    private fun toQifImport(
        id: String,
        originalFileName: String,
        importTimestampMs: Long,
        recordCount: Long,
        unsupportedCount: Long,
        accountType: String,
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
    ): QifImport =
        QifImport(
            id = QifImportId(Uuid.parse(id)),
            originalFileName = originalFileName,
            importTimestamp = Instant.fromEpochMilliseconds(importTimestampMs),
            recordCount = recordCount.toInt(),
            unsupportedCount = unsupportedCount.toInt(),
            accountType = accountType,
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
            applicationCount = applicationCount.toInt(),
            lastAppliedStrategyId = lastAppliedStrategyId?.let { CsvImportStrategyId(Uuid.parse(it)) },
            lastAppliedStrategyName = lastAppliedStrategyName,
            lastAppliedAt = lastAppliedAtMs?.let { Instant.fromEpochMilliseconds(it) },
        )
}
