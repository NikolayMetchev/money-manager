@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.domain.repository

import com.moneymanager.domain.model.ApiRequest
import com.moneymanager.domain.model.ApiResponse
import com.moneymanager.domain.model.ApiResponseId
import com.moneymanager.domain.model.ApiResponseTransaction
import com.moneymanager.domain.model.ApiSession
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.MonzoCredential
import com.moneymanager.domain.model.MonzoCredentialId
import kotlin.time.Instant

interface ApiSessionReadRepository {
    /**
     * Returns all credentials, newest first.
     */
    suspend fun getAllCredentials(): List<MonzoCredential>

    /**
     * Returns all sessions for the given credential, newest first.
     */
    suspend fun getSessionsByCredential(credentialId: MonzoCredentialId): List<ApiSession>

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
     * Returns all sessions that are not expired.
     *
     * @param now The current timestamp used to check expiry
     */
    suspend fun getSessions(now: Instant): List<ApiSession>

    /**
     * Returns all requests for the given session, newest first.
     */
    suspend fun getRequestsBySession(sessionId: ApiSessionId): List<ApiRequest>

    /**
     * Returns all responses for the given session, newest first.
     */
    suspend fun getResponsesBySession(sessionId: ApiSessionId): List<ApiResponse>

    /**
     * Returns all [ApiResponseTransaction] records for the given response, ordered by id.
     */
    suspend fun getResponseTransactions(responseId: ApiResponseId): List<ApiResponseTransaction>

    /**
     * Returns all [ApiResponseTransaction] records for responses in the given session, ordered by response then id.
     */
    suspend fun getResponseTransactionsBySession(sessionId: ApiSessionId): List<ApiResponseTransaction>

    /**
     * Returns all imported (session, revision) pairs.
     */
    suspend fun getImportedSessionRevisions(): Set<ApiSessionImportRevision>
}

data class ApiSessionImportRevision(
    val sessionId: ApiSessionId,
    val revisionId: Long,
)
