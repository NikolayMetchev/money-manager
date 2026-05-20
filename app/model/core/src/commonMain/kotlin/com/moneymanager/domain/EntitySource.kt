package com.moneymanager.domain

import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.JsonPath
import com.moneymanager.domain.model.SourceRecorder
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.CsvImportId

interface EntitySource {
    fun record(
        entityType: EntityType,
        entityId: Long,
        revisionId: Long,
    )

    fun recordFromApi(
        entityType: EntityType,
        entityId: Long,
        revisionId: Long,
        sessionId: ApiSessionId,
        requestId: ApiRequestId,
        jsonPath: JsonPath,
    )

    fun recordFromApiBatch(records: List<ApiEntitySourceRecord>) {
        records.forEach { record ->
            recordFromApi(
                entityType = record.entityType,
                entityId = record.entityId,
                revisionId = record.revisionId,
                sessionId = record.sessionId,
                requestId = record.requestId,
                jsonPath = record.jsonPath,
            )
        }
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
}

data class ApiEntitySourceRecord(
    val entityType: EntityType,
    val entityId: Long,
    val revisionId: Long,
    val sessionId: ApiSessionId,
    val requestId: ApiRequestId,
    val jsonPath: JsonPath,
)
