package com.moneymanager.rest

import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.insertApiRequest
import com.moneymanager.importengineapi.insertApiResponse

/**
 * Records API request/response traffic through the [ImportEngine] (the single DB writer) rather than a
 * write repository, so even this mid-HTTP bookkeeping passes the engine's edit gate. Each call issues a
 * one-item import batch and reads the generated id back.
 */
class ApiSessionTrafficRecorder(
    private val sessionId: ApiSessionId,
    private val importEngine: ImportEngine,
) : ApiTrafficRecorder {
    override suspend fun recordRequest(
        method: String,
        url: String,
        headers: Map<String, String>,
    ): Long =
        importEngine
            .insertApiRequest(
                sessionId = sessionId,
                method = method,
                url = url,
                headers = headers,
            ).id

    override suspend fun recordResponse(
        requestId: Long,
        body: String,
    ): Long =
        importEngine
            .insertApiResponse(
                requestId = ApiRequestId(requestId),
                sessionId = sessionId,
                json = body,
            ).id
}
