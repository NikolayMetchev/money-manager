@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.domain.model

import com.moneymanager.domain.model.csv.CsvImportId

/**
 * Interface for inserting transfer source records.
 * Implemented by the database layer to provide actual insertion capability.
 */
interface SourceInserter {
    fun insertManual(
        transactionId: String,
        revisionId: Long,
        deviceId: Long,
        createdAt: Long,
    )

    fun insertSampleGenerator(
        transactionId: String,
        revisionId: Long,
        deviceId: Long,
        createdAt: Long,
    )

    fun insertCsvImport(
        transactionId: String,
        revisionId: Long,
        deviceId: Long,
        csvImportId: String,
        csvRowIndex: Long,
        createdAt: Long,
    )
}

/**
 * Strategy for recording transfer source information.
 * Each implementation knows how to insert itself using the provided inserter.
 */
sealed interface SourceRecorder {
    /**
     * Insert the source record for the given transfer.
     */
    fun insert(
        transfer: Transfer,
        deviceId: Long,
        createdAt: Long,
        inserter: SourceInserter,
    )

    /**
     * Manual entry from UI.
     */
    data object Manual : SourceRecorder {
        override fun insert(
            transfer: Transfer,
            deviceId: Long,
            createdAt: Long,
            inserter: SourceInserter,
        ) {
            inserter.insertManual(transfer.id.id.toString(), transfer.revisionId, deviceId, createdAt)
        }
    }

    /**
     * Generated sample data.
     */
    data object SampleGenerator : SourceRecorder {
        override fun insert(
            transfer: Transfer,
            deviceId: Long,
            createdAt: Long,
            inserter: SourceInserter,
        ) {
            inserter.insertSampleGenerator(transfer.id.id.toString(), transfer.revisionId, deviceId, createdAt)
        }
    }

    /**
     * CSV import with row tracking.
     *
     * @param csvImportId The ID of the CSV import
     * @param rowIndexForTransfer Function to get the row index for each transfer
     */
    data class CsvImport(
        val csvImportId: CsvImportId,
        val rowIndexForTransfer: (TransferId) -> Long,
    ) : SourceRecorder {
        override fun insert(
            transfer: Transfer,
            deviceId: Long,
            createdAt: Long,
            inserter: SourceInserter,
        ) {
            inserter.insertCsvImport(
                transactionId = transfer.id.id.toString(),
                revisionId = transfer.revisionId,
                deviceId = deviceId,
                csvImportId = csvImportId.id.toString(),
                csvRowIndex = rowIndexForTransfer(transfer.id),
                createdAt = createdAt,
            )
        }
    }
}
