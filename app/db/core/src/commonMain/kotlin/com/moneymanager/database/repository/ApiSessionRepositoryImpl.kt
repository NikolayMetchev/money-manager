@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.repository

import com.moneymanager.database.sql.MoneyManagerDatabase
import com.moneymanager.domain.model.ApiRequest
import com.moneymanager.domain.model.ApiRequestHeader
import com.moneymanager.domain.model.ApiRequestHeaderId
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiResponse
import com.moneymanager.domain.model.ApiResponseId
import com.moneymanager.domain.model.ApiSession
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.repository.ApiSessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Instant

class ApiSessionRepositoryImpl(
    database: MoneyManagerDatabase,
) : ApiSessionRepository {
    private val queries = database.apiSessionQueries

    override suspend fun createSession(
        token: String,
        deviceId: DeviceId,
        createdAt: Instant,
        expiresAt: Instant?,
    ): ApiSessionId =
        withContext(Dispatchers.Default) {
            val id =
                queries.transactionWithResult {
                    queries.insert(
                        token = token,
                        device_id = deviceId.id,
                        created_at = createdAt.toEpochMilliseconds(),
                        expires_at = expiresAt?.toEpochMilliseconds(),
                    )
                    queries.lastInsertRowId().executeAsOne()
                }
            ApiSessionId(id)
        }

    override suspend fun getSessionById(id: ApiSessionId): ApiSession? =
        withContext(Dispatchers.Default) {
            queries.selectById(id.id).executeAsOneOrNull()?.toApiSession()
        }

    override suspend fun getSessionByToken(token: String): ApiSession? =
        withContext(Dispatchers.Default) {
            queries.selectByToken(token).executeAsOneOrNull()?.toApiSession()
        }

    override suspend fun getSessionsByDevice(deviceId: DeviceId): List<ApiSession> =
        withContext(Dispatchers.Default) {
            queries.selectByDeviceId(deviceId.id).executeAsList().map { it.toApiSession() }
        }

    override suspend fun getActiveSessions(now: Instant): List<ApiSession> =
        withContext(Dispatchers.Default) {
            queries.selectActiveSessions(now.toEpochMilliseconds()).executeAsList().map { it.toApiSession() }
        }

    override suspend fun revokeSession(
        id: ApiSessionId,
        revokedAt: Instant,
    ): Unit =
        withContext(Dispatchers.Default) {
            queries.revoke(
                revoked_at = revokedAt.toEpochMilliseconds(),
                id = id.id,
            )
        }

    override suspend fun revokeSessionByToken(
        token: String,
        revokedAt: Instant,
    ): Unit =
        withContext(Dispatchers.Default) {
            queries.revokeByToken(
                revoked_at = revokedAt.toEpochMilliseconds(),
                token = token,
            )
        }

    override suspend fun revokeAllSessionsForDevice(
        deviceId: DeviceId,
        revokedAt: Instant,
    ): Unit =
        withContext(Dispatchers.Default) {
            queries.revokeAllForDevice(
                revoked_at = revokedAt.toEpochMilliseconds(),
                device_id = deviceId.id,
            )
        }

    override suspend fun insertRequest(
        sessionId: ApiSessionId,
        requestedAt: Instant,
        json: String,
        headers: Map<String, String>,
    ): ApiRequestId =
        withContext(Dispatchers.Default) {
            val id =
                queries.transactionWithResult {
                    queries.insertRequest(
                        session_id = sessionId.id,
                        requested_at = requestedAt.toEpochMilliseconds(),
                        json = json,
                    )
                    val requestId = queries.lastInsertRowId().executeAsOne()

                    headers.forEach { (key, value) ->
                        queries.insertRequestHeader(
                            request_id = requestId,
                            key = key,
                            value_ = value,
                        )
                    }

                    requestId
                }
            ApiRequestId(id)
        }

    override suspend fun getRequestsBySession(sessionId: ApiSessionId): List<ApiRequest> =
        withContext(Dispatchers.Default) {
            val requests = queries.selectRequestsBySession(sessionId.id).executeAsList()
            val requestIds = requests.map { it.id }
            val headersByRequestId =
                if (requestIds.isEmpty()) {
                    emptyMap()
                } else {
                    queries
                        .selectHeadersByRequestIds(requestIds)
                        .executeAsList()
                        .groupBy { it.request_id }
                }

            requests.map { request ->
                request.toApiRequest(headersByRequestId.getValueOrDefault(request.id))
            }
        }

    override suspend fun insertResponse(
        sessionId: ApiSessionId,
        respondedAt: Instant,
        json: String,
    ): ApiResponseId =
        withContext(Dispatchers.Default) {
            val id =
                queries.transactionWithResult {
                    queries.insertResponse(
                        session_id = sessionId.id,
                        responded_at = respondedAt.toEpochMilliseconds(),
                        json = json,
                    )
                    queries.lastInsertRowId().executeAsOne()
                }
            ApiResponseId(id)
        }

    override suspend fun getResponsesBySession(sessionId: ApiSessionId): List<ApiResponse> =
        withContext(Dispatchers.Default) {
            queries.selectResponsesBySession(sessionId.id).executeAsList().map { it.toApiResponse() }
        }

    override suspend fun deleteSession(id: ApiSessionId): Unit =
        withContext(Dispatchers.Default) {
            queries.delete(id.id)
        }

    private fun com.moneymanager.database.sql.Api_request.toApiRequest(
        headers: List<com.moneymanager.database.sql.Api_request_header>,
    ): ApiRequest =
        ApiRequest(
            id = ApiRequestId(id),
            sessionId = ApiSessionId(session_id),
            requestedAt = Instant.fromEpochMilliseconds(requested_at),
            json = json,
            headers = headers.map { it.toApiRequestHeader() },
        )

    private fun com.moneymanager.database.sql.Api_request_header.toApiRequestHeader(): ApiRequestHeader =
        ApiRequestHeader(
            id = ApiRequestHeaderId(id),
            requestId = ApiRequestId(request_id),
            key = key,
            value = value_,
        )

    private fun com.moneymanager.database.sql.Api_response.toApiResponse(): ApiResponse =
        ApiResponse(
            id = ApiResponseId(id),
            sessionId = ApiSessionId(session_id),
            respondedAt = Instant.fromEpochMilliseconds(responded_at),
            json = json,
        )

    private fun com.moneymanager.database.sql.Api_session.toApiSession(): ApiSession =
        ApiSession(
            id = ApiSessionId(id),
            token = token,
            deviceId = DeviceId(device_id),
            createdAt = Instant.fromEpochMilliseconds(created_at),
            expiresAt = expires_at?.let { Instant.fromEpochMilliseconds(it) },
            revokedAt = revoked_at?.let { Instant.fromEpochMilliseconds(it) },
        )

    private fun <K, V> Map<K, List<V>>.getValueOrDefault(key: K): List<V> = get(key).orEmpty()
}
