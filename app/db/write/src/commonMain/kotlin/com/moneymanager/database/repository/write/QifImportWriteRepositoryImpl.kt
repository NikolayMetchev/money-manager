package com.moneymanager.database.repository.write

import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.QifImportId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.qif.QifImportRecord
import com.moneymanager.domain.repository.QifImportReadRepository
import com.moneymanager.domain.repository.write.QifImportWriteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

class QifImportWriteRepositoryImpl(
    private val database: MoneyManagerDatabaseWrapper,
    private val deviceId: DeviceId,
    reader: QifImportReadRepository,
    private val coroutineContext: CoroutineContext = Dispatchers.Default,
) : QifImportWriteRepository,
    QifImportReadRepository by reader {
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

    override suspend fun deleteImport(id: QifImportId): Unit =
        withContext(coroutineContext) {
            writeQueries.deleteImport(id.id.toString())
        }

    override suspend fun setImportIgnored(
        id: QifImportId,
        ignored: Boolean,
    ): Unit =
        withContext(coroutineContext) {
            writeQueries.setImportIgnored(if (ignored) 1L else 0L, id.id.toString())
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
}
