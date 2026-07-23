package com.moneymanager.domain.repository

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.ApiCredential
import com.moneymanager.domain.model.ApiCredentialId
import com.moneymanager.domain.model.ApiRequest
import com.moneymanager.domain.model.ApiResponse
import com.moneymanager.domain.model.ApiResponseId
import com.moneymanager.domain.model.ApiResponseTransaction
import com.moneymanager.domain.model.ApiSession
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.TransferId
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

interface ApiSessionReadRepository {
    /**
     * Returns all credentials, newest first.
     */
    suspend fun getAllCredentials(): List<ApiCredential>

    /**
     * All credentials, newest first, re-emitted on every change — for UI that must reflect an API
     * becoming connected without a manual refresh.
     */
    fun getCredentialsFlow(): Flow<List<ApiCredential>>

    /**
     * Returns all sessions for the given credential, newest first.
     */
    suspend fun getSessionsByCredential(credentialId: ApiCredentialId): List<ApiSession>

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

    /**
     * Accounts created (not merely touched) by the given session — used by re-import to scope
     * empty-account cleanup to what the session itself produced.
     */
    suspend fun getAccountIdsCreatedBySession(sessionId: ApiSessionId): Set<AccountId>

    /**
     * Transfers created (not merely touched) by the given session — used by re-import to scope
     * deletes to what the session itself produced.
     */
    suspend fun getTransferIdsCreatedBySession(sessionId: ApiSessionId): Set<TransferId>

    /**
     * Trades created (not merely touched) by the given session — used by re-import to scope
     * deletes to what the session itself produced.
     */
    suspend fun getTradeIdsCreatedBySession(sessionId: ApiSessionId): Set<TradeId>
}

data class ApiSessionImportRevision(
    val sessionId: ApiSessionId,
    val revisionId: Long,
)
