@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class, kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.repository

import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.database.mapper.TransferSourceFromRevisionMapper
import com.moneymanager.database.mapper.TransferSourceFromTransactionIdMapper
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.TransferSource
import com.moneymanager.domain.model.csv.CsvImportId
import com.moneymanager.domain.repository.CsvImportSourceRecord
import com.moneymanager.domain.repository.DeviceRepository
import com.moneymanager.domain.repository.SampleGeneratorSourceRecord
import com.moneymanager.domain.repository.TransferSourceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/**
 * Implementation of TransferSourceRepository using SQLDelight.
 * Manages transfer source records for tracking provenance.
 */
class TransferSourceRepositoryImpl(
    private val database: MoneyManagerDatabaseWrapper,
    private val deviceRepository: DeviceRepository,
) : TransferSourceRepository {
    private val queries = database.transferSourceQueries
    private val csvImportQueries = database.csvImportQueries

    override suspend fun recordManualSource(
        transactionId: TransferId,
        revisionId: Long,
        deviceInfo: DeviceInfo,
    ): TransferSource =
        withContext(Dispatchers.Default) {
            val deviceId = deviceRepository.getOrCreateDevice(deviceInfo)
            val now = Clock.System.now()

            queries.insertManual(
                transactionId = transactionId.toString(),
                revisionId = revisionId,
                deviceId = deviceId,
                createdAt = now.toEpochMilliseconds(),
            )

            queries.selectByTransactionIdAndRevision(transactionId.toString(), revisionId)
                .executeAsOne()
                .let(TransferSourceFromRevisionMapper::map)
        }

    override suspend fun recordCsvImportSource(
        transactionId: TransferId,
        revisionId: Long,
        csvImportId: CsvImportId,
        rowIndex: Long,
    ): TransferSource =
        withContext(Dispatchers.Default) {
            // Get device_id from the CSV import metadata
            val csvImport =
                csvImportQueries.selectImportByTableName(
                    csvImportQueries.selectImportById(csvImportId.toString())
                        .executeAsOne().tableName,
                ).executeAsOne()

            val now = Clock.System.now()
            queries.insertCsvImport(
                transactionId = transactionId.toString(),
                revisionId = revisionId,
                deviceId = csvImport.device_id,
                csvImportId = csvImportId.toString(),
                csvRowIndex = rowIndex,
                createdAt = now.toEpochMilliseconds(),
            )
            queries.selectByTransactionIdAndRevision(transactionId.toString(), revisionId)
                .executeAsOne()
                .let(TransferSourceFromRevisionMapper::map)
        }

    override suspend fun recordCsvImportSourcesBatch(
        csvImportId: CsvImportId,
        sources: List<CsvImportSourceRecord>,
    ): Unit =
        withContext(Dispatchers.Default) {
            // Get device_id from the CSV import metadata
            val csvImport = csvImportQueries.selectImportById(csvImportId.toString()).executeAsOne()
            val deviceId = csvImport.device_id

            val now = Clock.System.now()
            queries.transaction {
                sources.forEach { source ->
                    queries.insertCsvImport(
                        transactionId = source.transactionId.toString(),
                        revisionId = source.revisionId,
                        deviceId = deviceId,
                        csvImportId = csvImportId.toString(),
                        csvRowIndex = source.rowIndex,
                        createdAt = now.toEpochMilliseconds(),
                    )
                }
            }
        }

    override suspend fun getSourcesForTransaction(transactionId: TransferId): List<TransferSource> =
        withContext(Dispatchers.Default) {
            queries.selectAllByTransactionId(transactionId.toString())
                .executeAsList()
                .map(TransferSourceFromTransactionIdMapper::map)
        }

    override suspend fun getSourceByRevision(
        transactionId: TransferId,
        revisionId: Long,
    ): TransferSource? =
        withContext(Dispatchers.Default) {
            queries.selectByTransactionIdAndRevision(transactionId.toString(), revisionId)
                .executeAsOneOrNull()
                ?.let(TransferSourceFromRevisionMapper::map)
        }

    override suspend fun recordSampleGeneratorSourcesBatch(
        deviceInfo: DeviceInfo,
        sources: List<SampleGeneratorSourceRecord>,
    ): Unit =
        withContext(Dispatchers.Default) {
            val deviceId = deviceRepository.getOrCreateDevice(deviceInfo)
            val now = Clock.System.now()

            queries.transaction {
                sources.forEach { source ->
                    queries.insertSampleGenerator(
                        transactionId = source.transactionId.toString(),
                        revisionId = source.revisionId,
                        deviceId = deviceId,
                        createdAt = now.toEpochMilliseconds(),
                    )
                }
            }
        }
}
