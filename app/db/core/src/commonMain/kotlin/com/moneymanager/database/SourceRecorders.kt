@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database

import com.moneymanager.database.sql.TransferSourceQueries
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.JsonPath
import com.moneymanager.domain.model.SourceRecorder
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.CsvImportId
import com.moneymanager.domain.model.qif.QifImportId

/** Manual entry from UI. */
class ManualSourceRecorder(
    private val queries: TransferSourceQueries,
    private val deviceId: DeviceId,
) : SourceRecorder {
    override fun insert(transfer: Transfer) {
        queries.insertManual(
            transfer.id.id,
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
            transfer.id.id,
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
        queries.transaction {
            queries.insertCsvImportBase(
                transfer.id.id,
                transfer.revisionId,
                deviceId.id,
            )
            val transferSourceId = queries.lastInsertedId().executeAsOne()
            queries.insertCsvImportDetails(
                transferSourceId,
                csvImportId.id.toString(),
                rowIndexForTransfer(transfer.id),
            )
        }
    }
}

/** QIF import with record tracking. */
class QifImportSourceRecorder(
    private val queries: TransferSourceQueries,
    private val deviceId: DeviceId,
    private val qifImportId: QifImportId,
    private val recordIndexForTransfer: (TransferId) -> Long,
) : SourceRecorder {
    override fun insert(transfer: Transfer) {
        queries.transaction {
            queries.insertQifImportBase(
                transfer.id.id,
                transfer.revisionId,
                deviceId.id,
            )
            val transferSourceId = queries.lastInsertedId().executeAsOne()
            queries.insertQifImportDetails(
                transferSourceId,
                qifImportId.id.toString(),
                recordIndexForTransfer(transfer.id),
            )
        }
    }
}

/** API import with session and request tracking. */
class ApiImportSourceRecorder(
    private val queries: TransferSourceQueries,
    private val deviceId: DeviceId,
    private val sessionId: ApiSessionId,
    private val requestId: ApiRequestId,
    private val jsonPath: JsonPath,
) : SourceRecorder {
    /** The ID of the transfer inserted by the most recent [insert] call. */
    var insertedTransferId: TransferId? = null
        private set

    override fun insert(transfer: Transfer) {
        insertedTransferId = transfer.id
        queries.transaction {
            queries.insertApiBase(
                transfer.id.id,
                transfer.revisionId,
                deviceId.id,
            )
            val transferSourceId = queries.lastInsertedId().executeAsOne()
            queries.insertApiDetails(
                transferSourceId,
                sessionId.id,
                requestId.id,
                jsonPath.value,
            )
        }
    }
}
