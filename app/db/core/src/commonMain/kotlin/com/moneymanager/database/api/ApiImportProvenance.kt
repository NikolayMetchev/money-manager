package com.moneymanager.database.api

import com.moneymanager.domain.EntitySource
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.JsonPath
import com.moneymanager.domain.model.SourceRecorder
import com.moneymanager.domain.model.Transfer
import com.moneymanager.importmodel.ImportProvenance
import com.moneymanager.importmodel.ImportRowKey

/**
 * [ImportProvenance] for API imports: records each transfer's source via
 * [EntitySource.apiImportRecorder] using the per-row (requestId, jsonPath) carried by
 * [ImportRowKey.ApiJsonPath]. Account/person provenance is handled by the API import service itself,
 * so [recordEntity] is a no-op here.
 */
class ApiImportProvenance(
    private val entitySource: EntitySource,
    private val sessionId: ApiSessionId,
) : ImportProvenance {
    override fun transferRecorder(orderedRowKeys: List<ImportRowKey>): SourceRecorder =
        object : SourceRecorder {
            private var callIndex = 0

            override fun insert(transfer: Transfer) {
                val key = orderedRowKeys[callIndex++]
                require(key is ImportRowKey.ApiJsonPath) { "Expected an API row key, got ${key::class.simpleName}" }
                entitySource
                    .apiImportRecorder(
                        sessionId = sessionId,
                        requestId = key.requestId,
                        jsonPath = JsonPath(key.jsonPath),
                    ).insert(transfer)
            }
        }

    override fun recordEntity(
        entityType: EntityType,
        entityId: Long,
        revisionId: Long,
    ) = Unit
}
