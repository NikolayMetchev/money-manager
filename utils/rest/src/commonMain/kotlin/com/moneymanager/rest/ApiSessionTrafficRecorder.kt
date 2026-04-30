package com.moneymanager.rest

import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.repository.ApiSessionRepository

class ApiSessionTrafficRecorder(
    private val sessionId: ApiSessionId,
    private val apiSessionRepository: ApiSessionRepository,
) : ApiTrafficRecorder {
    override suspend fun recordRequest(
        method: String,
        url: String,
        headers: Map<String, String>,
    ): Long =
        apiSessionRepository
            .insertRequest(
                sessionId = sessionId,
                method = method,
                url = url,
                headers = headers,
            ).id

    override suspend fun recordResponse(
        requestId: Long,
        body: String,
    ): Long =
        apiSessionRepository
            .insertResponse(
                requestId = ApiRequestId(requestId),
                sessionId = sessionId,
                json = body,
            ).id
}
