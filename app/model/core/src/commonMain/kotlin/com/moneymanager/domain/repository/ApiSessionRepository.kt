@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.domain.repository

import com.moneymanager.domain.model.ApiRequest
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiResponse
import com.moneymanager.domain.model.ApiResponseId
import com.moneymanager.domain.model.ApiResponseTransaction
import com.moneymanager.domain.model.ApiResponseTransactionId
import com.moneymanager.domain.model.ApiResponseTransactionState
import com.moneymanager.domain.model.ApiSession
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.ApiSessionKind
import com.moneymanager.domain.model.ApiSessionType
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.JsonPath
import com.moneymanager.domain.model.MonzoCredential
import com.moneymanager.domain.model.MonzoCredentialId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.apistrategy.ApiImportStrategyId
import kotlin.time.Instant

data class ApiResponseTransactionInsert(
    val responseId: ApiResponseId,
    val jsonPath: JsonPath,
    val state: ApiResponseTransactionState,
    val transactionId: TransferId?,
    val errorMessage: String?,
)

data class ApiSessionImportRevision(
    val sessionId: ApiSessionId,
    val revisionId: Long,
)

interface ApiSessionRepository {
    /**
     * Creates a new credential (saved token), carrying its own [strategyId] and optional signing
     * keys, and returns its generated ID. A credential's identity is this generated ID, not the raw
     * token; the token column is globally unique, so attempting to save the same token twice fails
     * rather than merging into — or silently overwriting the strategy/keys of — an existing row.
     *
     * @param strategyId Optional link to the API import strategy this credential uses.
     */
    suspend fun createCredential(
        token: String,
        createdAt: Instant,
        type: ApiSessionType = ApiSessionType.MONZO,
        strategyId: ApiImportStrategyId? = null,
        privateKey: String? = null,
        publicKey: String? = null,
    ): MonzoCredentialId

    /**
     * Updates the strategy associated with a credential.
     */
    suspend fun updateCredentialStrategy(
        credentialId: MonzoCredentialId,
        strategyId: ApiImportStrategyId?,
    )

    /**
     * Stores (or replaces) the PEM-encoded RSA signing key pair on a credential.
     */
    suspend fun updateCredentialKeys(
        credentialId: MonzoCredentialId,
        privateKey: String?,
        publicKey: String?,
    )

    /**
     * Returns all credentials, newest first.
     */
    suspend fun getAllCredentials(): List<MonzoCredential>

    /**
     * Returns all sessions for the given credential, newest first.
     */
    suspend fun getSessionsByCredential(credentialId: MonzoCredentialId): List<ApiSession>

    /**
     * Creates a new API session for the given device.
     *
     * @param token The session token
     * @param deviceId The device that owns this session
     * @param createdAt The creation timestamp
     * @param expiresAt Optional expiry timestamp; null means the session never expires
     * @param credentialId Optional credential this session was created from
     * @param kind Whether this session downloaded accounts or transactions
     * @return The ID of the newly created session
     */
    suspend fun createSession(
        token: String,
        deviceId: DeviceId,
        createdAt: Instant,
        expiresAt: Instant?,
        type: ApiSessionType = ApiSessionType.MONZO,
        credentialId: MonzoCredentialId? = null,
        kind: ApiSessionKind? = null,
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
     * Returns all sessions that are not expired.
     *
     * @param now The current timestamp used to check expiry
     */
    suspend fun getSessions(now: Instant): List<ApiSession>

    /**
     * Stores one API request payload and its headers for the given session.
     */
    suspend fun insertRequest(
        sessionId: ApiSessionId,
        method: String,
        url: String,
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
        requestId: ApiRequestId,
        sessionId: ApiSessionId,
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

    /**
     * Records the import state of a single transaction entry found in an API response.
     *
     * @param responseId The ID of the API response containing the transaction
     * @param jsonPath JSONPath expression locating the transaction within the response body
     * @param state The import state (IMPORTED, DUPLICATE, or ERROR)
     * @param transactionId For IMPORTED: the new transaction ID. For DUPLICATE: the
     *   pre-existing transaction ID that this entry duplicates. Null for ERROR.
     * @param errorMessage For ERROR state: description of what went wrong
     * @return The ID of the newly created record
     */
    suspend fun insertResponseTransaction(
        responseId: ApiResponseId,
        jsonPath: JsonPath,
        state: ApiResponseTransactionState,
        transactionId: TransferId?,
        errorMessage: String?,
    ): ApiResponseTransactionId

    /**
     * Records import states for many transaction entries in one database transaction.
     */
    suspend fun insertResponseTransactions(transactions: List<ApiResponseTransactionInsert>)

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
     * Marks the given session as imported at the given timestamp.
     * Returns the number of rows updated (0 if the session id doesn't exist).
     */
    suspend fun markSessionImported(
        id: ApiSessionId,
        revisionId: Long,
        importedAt: Instant,
        importDurationMillis: Long? = null,
    ): Long
}
