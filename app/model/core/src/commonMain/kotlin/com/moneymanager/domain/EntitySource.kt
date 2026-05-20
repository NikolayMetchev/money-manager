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

    fun recordFromApi(record: ApiEntitySourceRecord)

    fun recordFromApi(
        entityType: EntityType,
        entityId: Long,
        revisionId: Long,
        sessionId: ApiSessionId,
        requestId: ApiRequestId,
        jsonPath: JsonPath,
    ) = recordFromApi(
        ApiEntitySourceRecord(
            entityType = entityType,
            entityId = entityId,
            revisionId = revisionId,
            sessionId = sessionId,
            requestId = requestId,
            jsonPath = jsonPath,
        ),
    )

    fun recordFromApiBatch(records: List<ApiEntitySourceRecord>) {
        records.forEach { record ->
            recordFromApi(record)
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
