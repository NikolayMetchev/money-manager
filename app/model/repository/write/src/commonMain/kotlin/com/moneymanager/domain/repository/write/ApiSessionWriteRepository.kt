package com.moneymanager.domain.repository.write

import com.moneymanager.domain.model.ApiCredentialId
import com.moneymanager.domain.model.ApiImportStrategyId
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiResponseId
import com.moneymanager.domain.model.ApiResponseTransactionId
import com.moneymanager.domain.model.ApiResponseTransactionInsert
import com.moneymanager.domain.model.ApiResponseTransactionState
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.JsonPath
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.ApiSessionReadRepository
import kotlin.time.Instant

interface ApiSessionWriteRepository : ApiSessionReadRepository {
    /**
     * Returns the connection row for [strategyId], creating it (stamped [createdAt]) if the strategy has
     * none yet. Idempotent, so a strategy reinstalled into a fresh database reconnects to the secrets
     * already in the credential vault without ever duplicating its connection. The secrets themselves
     * are never stored in the database.
     */
    suspend fun ensureCredential(
        strategyId: ApiImportStrategyId,
        createdAt: Instant,
    ): ApiCredentialId

    /**
     * Updates the strategy associated with a credential.
     */
    suspend fun updateCredentialStrategy(
        credentialId: ApiCredentialId,
        strategyId: ApiImportStrategyId?,
    )

    /**
     * Creates a new API session for the given device.
     *
     * @param deviceId The device that owns this session
     * @param createdAt The creation timestamp
     * @param expiresAt Optional expiry timestamp; null means the session never expires
     * @param credentialId Optional credential this session was created from
     * @return The ID of the newly created session
     */
    suspend fun createSession(
        deviceId: DeviceId,
        createdAt: Instant,
        expiresAt: Instant?,
        credentialId: ApiCredentialId? = null,
    ): ApiSessionId

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
     * Records that [endpointKey] is downloaded up to [coversUntil] for [sessionId] — the watermark a
     * later download of the same credential resumes from. Call this only once a whole download unit
     * has succeeded; see the `api_download_coverage` table comment for why this is not derived from
     * the recorded request/response rows.
     */
    suspend fun recordDownloadCoverage(
        sessionId: ApiSessionId,
        endpointKey: String,
        coversUntil: Instant,
    )

    /**
     * Stores one API response payload for the given session.
     */
    suspend fun insertResponse(
        requestId: ApiRequestId,
        sessionId: ApiSessionId,
        json: String,
    ): ApiResponseId

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
     * Deletes every `ApiResponseTransaction` recorded for responses in the given session — used
     * before a re-import re-inserts a fresh set, so the `(response_id, json_path)` unique index
     * never sees a stale row from a prior run.
     */
    suspend fun deleteResponseTransactionsBySession(sessionId: ApiSessionId)

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
