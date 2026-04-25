@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.domain.repository

import com.moneymanager.domain.model.ApiRequest
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiResponse
import com.moneymanager.domain.model.ApiResponseId
import com.moneymanager.domain.model.ApiSession
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.DeviceId
import kotlin.time.Instant

interface ApiSessionRepository {
    /**
     * Creates a new API session for the given device.
     *
     * @param token The session token
     * @param deviceId The device that owns this session
     * @param createdAt The creation timestamp
     * @param expiresAt Optional expiry timestamp; null means the session never expires
     * @return The ID of the newly created session
     */
    suspend fun createSession(
        token: String,
        deviceId: DeviceId,
        createdAt: Instant,
        expiresAt: Instant?,
    ): ApiSessionId

    /**
     * Returns the session with the given ID, or null if not found.
     */
    suspend fun getSessionById(id: ApiSessionId): ApiSession?

    /**
     * Returns the session with the given token, or null if not found.
     */
    suspend fun getSessionByToken(token: String): ApiSession?

    /**
     * Returns all sessions for the given device, ordered by creation time descending.
     */
    suspend fun getSessionsByDevice(deviceId: DeviceId): List<ApiSession>

    /**
     * Returns all sessions that are currently active (not revoked and not expired).
     *
     * @param now The current timestamp used to check expiry
     */
    suspend fun getActiveSessions(now: Instant): List<ApiSession>

    /**
     * Revokes the session with the given ID.
     *
     * @param id The session ID to revoke
     * @param revokedAt The revocation timestamp
     */
    suspend fun revokeSession(
        id: ApiSessionId,
        revokedAt: Instant,
    )

    /**
     * Revokes the session identified by the given token.
     *
     * @param token The session token to revoke
     * @param revokedAt The revocation timestamp
     */
    suspend fun revokeSessionByToken(
        token: String,
        revokedAt: Instant,
    )

    /**
     * Revokes all active sessions for the given device.
     *
     * @param deviceId The device whose sessions should be revoked
     * @param revokedAt The revocation timestamp
     */
    suspend fun revokeAllSessionsForDevice(
        deviceId: DeviceId,
        revokedAt: Instant,
    )

    /**
     * Stores one API request payload and its headers for the given session.
     */
    suspend fun insertRequest(
        sessionId: ApiSessionId,
        requestedAt: Instant,
        json: String,
        headers: Map<String, String>,
    ): ApiRequestId

    /**
     * Returns all requests for the given session, newest first.
     */
    suspend fun getRequestsBySession(sessionId: ApiSessionId): List<ApiRequest>

    /**
     * Stores one API response payload for the given session.
     */
    suspend fun insertResponse(
        sessionId: ApiSessionId,
        respondedAt: Instant,
        json: String,
    ): ApiResponseId

    /**
     * Returns all responses for the given session, newest first.
     */
    suspend fun getResponsesBySession(sessionId: ApiSessionId): List<ApiResponse>

    /**
     * Deletes the session with the given ID permanently.
     */
    suspend fun deleteSession(id: ApiSessionId)
}
