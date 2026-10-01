package com.moneymanager.importengineapi

import com.moneymanager.domain.model.ApiCredentialId
import com.moneymanager.domain.model.ApiImportStrategyId
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiResponseId
import com.moneymanager.domain.model.ApiResponseTransactionInsert
import com.moneymanager.domain.model.ApiResponseTransactionState
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.JsonPath
import com.moneymanager.domain.model.TransferId
import kotlin.time.Instant

/**
 * Writes on the API session/credential/request/response tables. The engine applies these so the API
 * traffic recorder, connect flow and import service never hold an `ApiSessionWriteRepository`. Create
 * variants carry a [String] `key` whose generated id is read back from the matching map on
 * [ImportResult].
 */
sealed interface ApiSessionMutation {
    /**
     * Gets or creates the (secret-free) connection row for [strategyId]; the id is read back under [key].
     * The secrets themselves go to the credential vault, never through the engine.
     */
    data class EnsureCredential(
        val key: String,
        val strategyId: ApiImportStrategyId,
        val createdAt: Instant,
    ) : ApiSessionMutation

    data class UpdateCredentialStrategy(
        val credentialId: ApiCredentialId,
        val strategyId: ApiImportStrategyId?,
    ) : ApiSessionMutation

    data class CreateSession(
        val key: String,
        val deviceId: DeviceId,
        val createdAt: Instant,
        val credentialId: ApiCredentialId? = null,
    ) : ApiSessionMutation

    data class InsertRequest(
        val key: String,
        val sessionId: ApiSessionId,
        val method: String,
        val url: String,
        val headers: Map<String, String>,
    ) : ApiSessionMutation

    /**
     * Records that a download unit for [endpointKey] completed, covering data up to [coversUntil].
     */
    data class RecordDownloadCoverage(
        val sessionId: ApiSessionId,
        val endpointKey: String,
        val coversUntil: Instant,
    ) : ApiSessionMutation

    data class InsertResponse(
        val key: String,
        val requestId: ApiRequestId,
        val sessionId: ApiSessionId,
        val json: String,
    ) : ApiSessionMutation

    data class DeleteSession(
        val id: ApiSessionId,
    ) : ApiSessionMutation

    data class InsertResponseTransaction(
        val key: String,
        val responseId: ApiResponseId,
        val jsonPath: JsonPath,
        val state: ApiResponseTransactionState,
        val transactionId: TransferId?,
        val errorMessage: String?,
    ) : ApiSessionMutation

    data class InsertResponseTransactions(
        val transactions: List<ApiResponseTransactionInsert>,
    ) : ApiSessionMutation

    /**
     * Clears every `api_response_transaction` row for the session, so a re-import (fresh or
     * retroactive) can insert a clean set without violating the `(response_id, json_path)` unique
     * index against records from a prior run.
     */
    data class DeleteResponseTransactionsBySession(
        val sessionId: ApiSessionId,
    ) : ApiSessionMutation

    data class MarkSessionImported(
        val id: ApiSessionId,
        val revisionId: Long,
        val importedAt: Instant,
        val importDurationMillis: Long? = null,
    ) : ApiSessionMutation
}
