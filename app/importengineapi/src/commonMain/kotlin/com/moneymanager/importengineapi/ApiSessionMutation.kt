@file:OptIn(kotlin.time.ExperimentalTime::class)

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
    data class CreateCredential(
        val key: String,
        val token: String,
        val createdAt: Instant,
        val strategyId: ApiImportStrategyId? = null,
        val privateKey: String? = null,
        val publicKey: String? = null,
        /** HMAC secret for ApiAuthType.SIGNED strategies; [token] holds the api key. */
        val apiSecret: String? = null,
    ) : ApiSessionMutation

    data class UpdateCredentialStrategy(
        val credentialId: ApiCredentialId,
        val strategyId: ApiImportStrategyId?,
    ) : ApiSessionMutation

    data class UpdateCredentialKeys(
        val credentialId: ApiCredentialId,
        val privateKey: String?,
        val publicKey: String?,
    ) : ApiSessionMutation

    data class UpdateCredentialSecrets(
        val credentialId: ApiCredentialId,
        val token: String,
        val apiSecret: String?,
    ) : ApiSessionMutation

    data class CreateSession(
        val key: String,
        val token: String,
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

    data class MarkSessionImported(
        val id: ApiSessionId,
        val revisionId: Long,
        val importedAt: Instant,
        val importDurationMillis: Long? = null,
    ) : ApiSessionMutation
}
