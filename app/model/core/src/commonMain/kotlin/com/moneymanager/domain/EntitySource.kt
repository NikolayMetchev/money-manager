package com.moneymanager.domain

import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.JsonPath
import com.moneymanager.domain.model.SourceRecorder
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.CsvImportId
import com.moneymanager.domain.model.qif.QifImportId

/**
 * Provides per-source recorders for **transfers** and the device identity for the current source.
 *
 * Entity provenance (account/person/ownership/category/currency) is no longer recorded here — it is
 * recorded atomically inside the repository create/update methods via a required
 * [com.moneymanager.domain.model.EntityProvenance] parameter, so it can never be forgotten. Callers
 * use [deviceId] to build that provenance.
 */
interface EntitySource {
    /** The device this source records against; used to construct `EntityProvenance`. */
    val deviceId: DeviceId

    fun manualRecorder(): SourceRecorder

    fun sampleGeneratorRecorder(): SourceRecorder

    fun csvImportRecorder(
        csvImportId: CsvImportId,
        rowIndexForTransfer: (TransferId) -> Long,
    ): SourceRecorder

    fun apiImportRecorder(
        sessionId: ApiSessionId,
        requestId: ApiRequestId,
        jsonPath: JsonPath,
    ): SourceRecorder

    fun qifImportRecorder(
        qifImportId: QifImportId,
        recordIndexForTransfer: (TransferId) -> Long,
    ): SourceRecorder
}
