@file:OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)

package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.moneymanager.database.sql.qifImport.Qif_record
import com.moneymanager.database.sql.read.MoneyManagerDatabase
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import com.moneymanager.domain.model.qif.QifImport
import com.moneymanager.domain.model.qif.QifImportId
import com.moneymanager.domain.model.qif.QifImportRecord
import com.moneymanager.domain.model.qif.QifRecordSplit
import com.moneymanager.domain.repository.QifImportReadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.coroutines.CoroutineContext
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class QifImportReadRepositoryImpl(
    database: MoneyManagerDatabase,
    private val coroutineContext: CoroutineContext = Dispatchers.Default,
) : QifImportReadRepository {
    private val selectQueries = database.qifImportSelectQueries

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
                DeviceWriteRepositoryImpl.createDeviceInfo(
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
