@file:OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)

package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.database.sql.qifImport.Qif_record
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
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
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
    private val selectQueries = database.qifImportSelectQueries
    private val writeQueries = database.qifImportWriteQueries

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

                writeQueries.insertImport(
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
                    writeQueries.insertRecord(
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
        selectQueries
            .selectAllImports(::toQifImport)
            .asFlow()
            .mapToList(coroutineContext)

    override fun getImport(id: QifImportId): Flow<QifImport?> =
        selectQueries
            .selectImportById(id.id.toString(), ::toQifImport)
            .asFlow()
            .mapToOneOrNull(coroutineContext)

    override suspend fun getImportRecords(
        id: QifImportId,
        limit: Int,
        offset: Int,
    ): List<QifImportRecord> =
        withContext(coroutineContext) {
            selectQueries
                .selectRecordsByImportId(id.id.toString(), limit.toLong(), offset.toLong())
                .executeAsList()
                .map { it.toDomain() }
        }

    override suspend fun countRecords(id: QifImportId): Int =
        withContext(coroutineContext) {
            selectQueries.countRecords(id.id.toString()).executeAsOne().toInt()
        }

    override suspend fun deleteImport(id: QifImportId): Unit =
        withContext(coroutineContext) {
            writeQueries.deleteImport(id.id.toString())
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
                    writeQueries.updateRecordStatus(
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
            writeQueries.insertOrReplaceError(
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
                    writeQueries.deleteError(qif_import_id = importIdString, record_index = recordIndex)
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
            writeQueries.insertApplication(
                id = Uuid.random().toString(),
                qif_import_id = id.id.toString(),
                strategy_id = strategyId.id.toString(),
                strategy_name = strategyName,
                applied_at = appliedAt.toEpochMilliseconds(),
            )
        }

    override suspend fun findImportsByChecksum(checksum: String): List<QifImport> =
        withContext(coroutineContext) {
            selectQueries.selectImportsByChecksum(checksum, ::toQifImport).executeAsList()
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
            splits = decodeSplits(splits_json),
            transferId = transaction_id?.toLongOrNull()?.let { TransferId(it) },
            importStatus = import_status?.let { ImportStatus.valueOf(it) },
        )

    // Splits are stored as JSON; tolerate malformed/corrupt data by falling back to no splits
    // rather than failing the whole import view.
    private fun decodeSplits(splitsJson: String?): List<QifRecordSplit> =
        splitsJson
            ?.let {
                try {
                    Json.decodeFromString<List<QifRecordSplit>>(it)
                } catch (_: SerializationException) {
                    emptyList()
                }
            }.orEmpty()

    @Suppress("LongParameterList")
    private fun toQifImport(
        id: String,
        originalFileName: String,
        importTimestampMs: Long,
        recordCount: Long,
        unsupportedCount: Long,
        accountType: String,
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
