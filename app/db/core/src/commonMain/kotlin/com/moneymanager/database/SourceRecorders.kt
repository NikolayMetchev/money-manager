@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database

import com.moneymanager.database.sql.TransferSourceQueries
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.SourceRecorder
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.CsvImportId

/** Manual entry from UI. */
class ManualSourceRecorder(
    private val queries: TransferSourceQueries,
    private val deviceId: DeviceId,
) : SourceRecorder {
    override fun insert(transfer: Transfer) {
        queries.insertManual(
            transfer.id.id.toString(),
            transfer.revisionId,
            deviceId.id,
        )
    }
}

/** Generated sample data. */
class SampleGeneratorSourceRecorder(
    private val queries: TransferSourceQueries,
    private val deviceId: DeviceId,
) : SourceRecorder {
    override fun insert(transfer: Transfer) {
        queries.insertSampleGenerator(
            transfer.id.id.toString(),
            transfer.revisionId,
            deviceId.id,
        )
    }
}

/** CSV import with row tracking. */
class CsvImportSourceRecorder(
    private val queries: TransferSourceQueries,
    private val deviceId: DeviceId,
    private val csvImportId: CsvImportId,
    private val rowIndexForTransfer: (TransferId) -> Long,
) : SourceRecorder {
    override fun insert(transfer: Transfer) {
        // Insert base TransferSource record
        queries.insertCsvImportBase(
            transfer.id.id.toString(),
            transfer.revisionId,
            deviceId.id,
        )
        // Get the auto-generated ID and insert CSV-specific details
        val transferSourceId = queries.lastInsertedId().executeAsOne()
        queries.insertCsvImportDetails(
            transferSourceId,
            csvImportId.id.toString(),
            rowIndexForTransfer(transfer.id),
        )
    }
}
