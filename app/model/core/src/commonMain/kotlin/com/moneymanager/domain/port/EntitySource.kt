package com.moneymanager.domain.port

import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.JsonPath

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
}
