@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.repository

import com.moneymanager.database.sql.read.MoneyManagerDatabase
import com.moneymanager.domain.model.ApiRequest
import com.moneymanager.domain.model.ApiRequestHeader
import com.moneymanager.domain.model.ApiRequestHeaderId
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiResponse
import com.moneymanager.domain.model.ApiResponseId
import com.moneymanager.domain.model.ApiResponseTransaction
import com.moneymanager.domain.model.ApiResponseTransactionId
import com.moneymanager.domain.model.ApiResponseTransactionState
import com.moneymanager.domain.model.ApiSession
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.ApiSessionType
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.JsonPath
import com.moneymanager.domain.model.MonzoCredential
import com.moneymanager.domain.model.MonzoCredentialId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.apistrategy.ApiImportStrategyId
import com.moneymanager.domain.repository.ApiSessionImportRevision
import com.moneymanager.domain.repository.ApiSessionReadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ApiSessionReadRepositoryImpl(
    database: MoneyManagerDatabase,
) : ApiSessionReadRepository {
    private val selectQueries = database.apiSessionSelectQueries

    override suspend fun getAllCredentials(): List<MonzoCredential> =
        withContext(Dispatchers.Default) {
            selectQueries.selectAllCredentials().executeAsList().map { it.toMonzoCredential() }
        }

    override suspend fun getSessionsByCredential(credentialId: MonzoCredentialId): List<ApiSession> =
        withContext(Dispatchers.Default) {
            selectQueries.selectByCredentialId(credentialId.id).executeAsList().map { it.toApiSession() }
        }

    override suspend fun getSessionById(id: ApiSessionId): ApiSession? =
        withContext(Dispatchers.Default) {
            selectQueries.selectById(id.id).executeAsOneOrNull()?.toApiSession()
        }

    override suspend fun getSessionByToken(token: String): ApiSession? =
        withContext(Dispatchers.Default) {
            selectQueries.selectByToken(token).executeAsOneOrNull()?.toApiSession()
        }

    override suspend fun getSessionsByDevice(deviceId: DeviceId): List<ApiSession> =
        withContext(Dispatchers.Default) {
            selectQueries.selectByDeviceId(deviceId.id).executeAsList().map { it.toApiSession() }
        }

    override suspend fun getSessions(now: Instant): List<ApiSession> =
        withContext(Dispatchers.Default) {
            selectQueries.selectSessions(now.toEpochMilliseconds()).executeAsList().map { it.toApiSession() }
        }

    override suspend fun getRequestsBySession(sessionId: ApiSessionId): List<ApiRequest> =
        withContext(Dispatchers.Default) {
            val requests = selectQueries.selectRequestsBySession(sessionId.id).executeAsList()
            val requestIds = requests.map { it.id }
            val headersByRequestId =
                if (requestIds.isEmpty()) {
                    emptyMap()
                } else {
                    selectQueries
                        .selectHeadersByRequestIds(requestIds)
                        .executeAsList()
                        .groupBy { it.request_id }
                }

            requests.map { request ->
                request.toApiRequest(headersByRequestId.getValueOrDefault(request.id))
            }
        }

    override suspend fun getResponsesBySession(sessionId: ApiSessionId): List<ApiResponse> =
        withContext(Dispatchers.Default) {
            selectQueries.selectResponsesBySession(sessionId.id).executeAsList().map { it.toApiResponse() }
        }

    override suspend fun getResponseTransactions(responseId: ApiResponseId): List<ApiResponseTransaction> =
        withContext(Dispatchers.Default) {
            selectQueries.selectResponseTransactionsByResponseId(responseId.id).executeAsList().map { row ->
                ApiResponseTransaction(
                    id = ApiResponseTransactionId(row.id),
                    responseId = ApiResponseId(row.response_id),
                    jsonPath = JsonPath(row.json_path),
                    state = ApiResponseTransactionState.fromId(row.state.toInt()),
                    transactionId = row.transaction_id?.let(::TransferId),
                    errorMessage = row.error_message,
                )
            }
        }

    override suspend fun getResponseTransactionsBySession(sessionId: ApiSessionId): List<ApiResponseTransaction> =
        withContext(Dispatchers.Default) {
            selectQueries.selectResponseTransactionsBySession(sessionId.id).executeAsList().map { row ->
                ApiResponseTransaction(
                    id = ApiResponseTransactionId(row.id),
                    responseId = ApiResponseId(row.response_id),
                    jsonPath = JsonPath(row.json_path),
                    state = ApiResponseTransactionState.fromId(row.state.toInt()),
                    transactionId = row.transaction_id?.let(::TransferId),
                    errorMessage = row.error_message,
                )
            }
        }

    override suspend fun getImportedSessionRevisions(): Set<ApiSessionImportRevision> =
        withContext(Dispatchers.Default) {
            selectQueries
                .selectImportedSessionRevisions()
                .executeAsList()
                .map { ApiSessionImportRevision(ApiSessionId(it.session_id), it.revision_id) }
                .toSet()
        }

    private fun com.moneymanager.database.sql.apiSession.Api_request.toApiRequest(
        headers: List<com.moneymanager.database.sql.apiSession.Api_request_header>,
    ): ApiRequest =
        ApiRequest(
            id = ApiRequestId(id),
            sessionId = ApiSessionId(session_id),
            requestedAt = Instant.fromEpochMilliseconds(requested_at),
            method = method,
            url = url,
            headers = headers.map { it.toApiRequestHeader() },
        )

    private fun com.moneymanager.database.sql.apiSession.Api_request_header.toApiRequestHeader(): ApiRequestHeader =
        ApiRequestHeader(
            id = ApiRequestHeaderId(id),
            requestId = ApiRequestId(request_id),
            key = key,
            value = value_,
        )

    private fun com.moneymanager.database.sql.apiSession.Api_response.toApiResponse(): ApiResponse =
        ApiResponse(
            id = ApiResponseId(id),
            requestId = ApiRequestId(request_id),
            sessionId = ApiSessionId(session_id),
            respondedAt = Instant.fromEpochMilliseconds(responded_at),
            json = json,
        )

    private fun com.moneymanager.database.sql.apiSession.Api_credential.toMonzoCredential(): MonzoCredential =
        MonzoCredential(
            id = MonzoCredentialId(id),
            type = ApiSessionType.fromId(type_id),
            token = token,
            createdAt = Instant.fromEpochMilliseconds(created_at),
            strategyId = strategy_id?.let { ApiImportStrategyId(Uuid.parse(it)) },
            privateKey = private_key,
            publicKey = public_key,
            apiSecret = api_secret,
        )

    private fun com.moneymanager.database.sql.apiSession.Api_session_with_latest_import.toApiSession(): ApiSession =
        ApiSession(
            id = ApiSessionId(id),
            type = ApiSessionType.fromId(type_id),
            token = token,
            deviceId = DeviceId(device_id),
            createdAt = Instant.fromEpochMilliseconds(created_at),
            expiresAt = expires_at?.let { Instant.fromEpochMilliseconds(it) },
            credentialId = credential_id?.let { MonzoCredentialId(it) },
            importDurationMillis = import_duration_millis,
        )

    private fun <K, V> Map<K, List<V>>.getValueOrDefault(key: K): List<V> = get(key).orEmpty()
}
