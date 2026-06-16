package com.moneymanager.database.api

import com.moneymanager.domain.EntitySource
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.EntityProvenance
import com.moneymanager.domain.model.JsonPath
import com.moneymanager.domain.model.SourceRecorder
import com.moneymanager.domain.model.Transfer
import com.moneymanager.importengineapi.ImportProvenance
import com.moneymanager.importengineapi.ImportRowKey

/**
 * [ImportProvenance] for API imports: records each transfer's source via
 * [EntitySource.apiImportRecorder] using the per-row (requestId, jsonPath) carried by
 * [ImportRowKey.ApiJsonPath]. Account/person provenance is handled by the API import service itself
 * (each entity is created with an [EntityProvenance.ApiImport]), so API imports never create entities
 * through the engine and [entityProvenance] is unreachable.
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

    override fun entityProvenance(): EntityProvenance =
        error("API imports create accounts/people via the API import service, not the import engine")
}
