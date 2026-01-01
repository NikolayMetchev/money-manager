@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

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

            queries.insertManual(
                transaction_id = transactionId.toString(),
                revision_id = revisionId,
                device_id = deviceId.id,
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
                        .executeAsOne().table_name,
                ).executeAsOne()

            // Insert base TransferSource record
            queries.insertCsvImportBase(
                transaction_id = transactionId.toString(),
                revision_id = revisionId,
                device_id = csvImport.device_id,
            )
            // Get the auto-generated ID and insert CSV-specific details
            val transferSourceId = queries.lastInsertedId().executeAsOne()
            queries.insertCsvImportDetails(
                id = transferSourceId,
                csv_import_id = csvImportId.toString(),
                csv_row_index = rowIndex,
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

            queries.transaction {
                sources.forEach { source ->
                    // Insert base TransferSource record
                    queries.insertCsvImportBase(
                        transaction_id = source.transactionId.toString(),
                        revision_id = source.revisionId,
                        device_id = deviceId,
                    )
                    // Get the auto-generated ID and insert CSV-specific details
                    val transferSourceId = queries.lastInsertedId().executeAsOne()
                    queries.insertCsvImportDetails(
                        id = transferSourceId,
                        csv_import_id = csvImportId.toString(),
                        csv_row_index = source.rowIndex,
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

            queries.transaction {
                sources.forEach { source ->
                    queries.insertSampleGenerator(
                        transaction_id = source.transactionId.toString(),
                        revision_id = source.revisionId,
                        device_id = deviceId.id,
                    )
                }
            }
        }
}
