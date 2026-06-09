package com.moneymanager.domain

import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.JsonPath
import com.moneymanager.domain.model.SourceRecorder
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.CsvImportId
import com.moneymanager.domain.model.qif.QifImportId

interface EntitySource {
    fun record(
        entityType: EntityType,
        entityId: Long,
        revisionId: Long,
    )

    fun recordFromApi(record: ApiEntitySourceRecord)

    fun recordFromApiBatch(records: List<ApiEntitySourceRecord>) {
        records.forEach(::recordFromApi)
    }

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

data class ApiEntitySourceRecord(
    val entityType: EntityType,
    val entityId: Long,
    val revisionId: Long,
    val sessionId: ApiSessionId,
    val requestId: ApiRequestId,
    val jsonPath: JsonPath,
)
