@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database

import com.moneymanager.database.sql.TransferSourceQueries
import com.moneymanager.domain.model.SourceRecorder
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.CsvImportId

/** Manual entry from UI. */
class ManualSourceRecorder(
    private val queries: TransferSourceQueries,
    private val deviceId: Long,
) : SourceRecorder {
    override fun insert(transfer: Transfer) {
        queries.insertManual(
            transfer.id.id.toString(),
            transfer.revisionId,
            deviceId,
        )
    }
}

/** Generated sample data. */
class SampleGeneratorSourceRecorder(
    private val queries: TransferSourceQueries,
    private val deviceId: Long,
) : SourceRecorder {
    override fun insert(transfer: Transfer) {
        queries.insertSampleGenerator(
            transfer.id.id.toString(),
            transfer.revisionId,
            deviceId,
        )
    }
}

/** CSV import with row tracking. */
class CsvImportSourceRecorder(
    private val queries: TransferSourceQueries,
    private val deviceId: Long,
    private val csvImportId: CsvImportId,
    private val rowIndexForTransfer: (TransferId) -> Long,
) : SourceRecorder {
    override fun insert(transfer: Transfer) {
        queries.insertCsvImport(
            transfer.id.id.toString(),
            transfer.revisionId,
            deviceId,
            csvImportId.id.toString(),
            rowIndexForTransfer(transfer.id),
        )
    }
}
