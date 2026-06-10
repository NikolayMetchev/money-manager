@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.repository

import com.moneymanager.database.sql.MoneyManagerDatabase
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
import com.moneymanager.domain.repository.ApiResponseTransactionInsert
import com.moneymanager.domain.repository.ApiSessionImportRevision
import com.moneymanager.domain.repository.ApiSessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ApiSessionRepositoryImpl(
    database: MoneyManagerDatabase,
) : ApiSessionRepository {
    private val queries = database.apiSessionQueries

    override suspend fun createCredential(
        token: String,
        createdAt: Instant,
        type: ApiSessionType,
        strategyId: ApiImportStrategyId?,
        privateKey: String?,
        publicKey: String?,
    ): MonzoCredentialId =
        withContext(Dispatchers.Default) {
            val id =
                queries.transactionWithResult {
                    queries.insertCredential(
                        type_id = type.id,
                        token = token,
                        created_at = createdAt.toEpochMilliseconds(),
                        strategy_id = strategyId?.id?.toString(),
                        private_key = privateKey,
                        public_key = publicKey,
                    )
                    queries.lastInsertCredentialRowId().executeAsOne()
                }
            MonzoCredentialId(id)
        }

    override suspend fun updateCredentialStrategy(
        credentialId: MonzoCredentialId,
        strategyId: ApiImportStrategyId?,
    ): Unit =
        withContext(Dispatchers.Default) {
            val affected =
                queries
                    .updateCredentialStrategy(
                        strategy_id = strategyId?.id?.toString(),
                        id = credentialId.id,
                    ).await()
            check(affected == 1L) { "Expected to update one credential ($credentialId) strategy, but $affected rows matched" }
        }

    override suspend fun updateCredentialKeys(
        credentialId: MonzoCredentialId,
        privateKey: String?,
        publicKey: String?,
    ): Unit =
        withContext(Dispatchers.Default) {
            val affected =
                queries
                    .updateCredentialKeys(
                        private_key = privateKey,
                        public_key = publicKey,
                        id = credentialId.id,
                    ).await()
            check(affected == 1L) { "Expected to update one credential ($credentialId) keys, but $affected rows matched" }
        }

    override suspend fun getAllCredentials(): List<MonzoCredential> =
        withContext(Dispatchers.Default) {
            queries.selectAllCredentials().executeAsList().map { it.toMonzoCredential() }
        }

    override suspend fun getSessionsByCredential(credentialId: MonzoCredentialId): List<ApiSession> =
        withContext(Dispatchers.Default) {
            queries.selectByCredentialId(credentialId.id).executeAsList().map { it.toApiSession() }
        }

    override suspend fun createSession(
        token: String,
        deviceId: DeviceId,
        createdAt: Instant,
        expiresAt: Instant?,
        type: ApiSessionType,
        credentialId: MonzoCredentialId?,
    ): ApiSessionId =
        withContext(Dispatchers.Default) {
            val id =
                queries.transactionWithResult {
                    queries.insert(
                        type_id = type.id,
                        token = token,
                        device_id = deviceId.id,
                        created_at = createdAt.toEpochMilliseconds(),
                        expires_at = expiresAt?.toEpochMilliseconds(),
                        credential_id = credentialId?.id,
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

    override suspend fun getSessions(now: Instant): List<ApiSession> =
        withContext(Dispatchers.Default) {
            queries.selectSessions(now.toEpochMilliseconds()).executeAsList().map { it.toApiSession() }
        }

    override suspend fun insertRequest(
        sessionId: ApiSessionId,
        method: String,
        url: String,
        headers: Map<String, String>,
    ): ApiRequestId =
        withContext(Dispatchers.Default) {
            val id =
                queries.transactionWithResult {
                    queries.insertRequest(
                        session_id = sessionId.id,
                        method = method,
                        url = url,
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
        requestId: ApiRequestId,
        sessionId: ApiSessionId,
        json: String,
    ): ApiResponseId =
        withContext(Dispatchers.Default) {
            val id =
                queries.transactionWithResult {
                    queries.insertResponse(
                        request_id = requestId.id,
                        session_id = sessionId.id,
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

    override suspend fun insertResponseTransaction(
        responseId: ApiResponseId,
        jsonPath: JsonPath,
        state: ApiResponseTransactionState,
        transactionId: TransferId?,
        errorMessage: String?,
    ): ApiResponseTransactionId =
        withContext(Dispatchers.Default) {
            val id =
                queries.transactionWithResult {
                    queries.insertResponseTransaction(
                        response_id = responseId.id,
                        json_path = jsonPath.value,
                        state = state.id.toLong(),
                        transaction_id = transactionId?.id,
                        error_message = errorMessage,
                    )
                    queries.lastInsertApiResponseTransactionId().executeAsOne()
                }
            ApiResponseTransactionId(id)
        }

    override suspend fun insertResponseTransactions(transactions: List<ApiResponseTransactionInsert>): Unit =
        withContext(Dispatchers.Default) {
            if (transactions.isEmpty()) return@withContext

            queries.transaction {
                transactions.forEach { transaction ->
                    queries.insertResponseTransaction(
                        response_id = transaction.responseId.id,
                        json_path = transaction.jsonPath.value,
                        state = transaction.state.id.toLong(),
                        transaction_id = transaction.transactionId?.id,
                        error_message = transaction.errorMessage,
                    )
                }
            }
        }

    override suspend fun getResponseTransactions(responseId: ApiResponseId): List<ApiResponseTransaction> =
        withContext(Dispatchers.Default) {
            queries.selectResponseTransactionsByResponseId(responseId.id).executeAsList().map { row ->
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
            queries.selectResponseTransactionsBySession(sessionId.id).executeAsList().map { row ->
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
            queries
                .selectImportedSessionRevisions()
                .executeAsList()
                .map { ApiSessionImportRevision(ApiSessionId(it.session_id), it.revision_id) }
                .toSet()
        }

    override suspend fun markSessionImported(
        id: ApiSessionId,
        revisionId: Long,
        importedAt: Instant,
        importDurationMillis: Long?,
    ): Long =
        withContext(Dispatchers.Default) {
            queries
                .markSessionImported(
                    session_id = id.id,
                    revision_id = revisionId,
                    imported_at = importedAt.toEpochMilliseconds(),
                    import_duration_millis = importDurationMillis,
                ).await()
        }

    private fun com.moneymanager.database.sql.Api_request.toApiRequest(
        headers: List<com.moneymanager.database.sql.Api_request_header>,
    ): ApiRequest =
        ApiRequest(
            id = ApiRequestId(id),
            sessionId = ApiSessionId(session_id),
            requestedAt = Instant.fromEpochMilliseconds(requested_at),
            method = method,
            url = url,
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
            requestId = ApiRequestId(request_id),
            sessionId = ApiSessionId(session_id),
            respondedAt = Instant.fromEpochMilliseconds(responded_at),
            json = json,
        )

    private fun com.moneymanager.database.sql.Api_credential.toMonzoCredential(): MonzoCredential =
        MonzoCredential(
            id = MonzoCredentialId(id),
            type = ApiSessionType.fromId(type_id),
            token = token,
            createdAt = Instant.fromEpochMilliseconds(created_at),
            strategyId = strategy_id?.let { ApiImportStrategyId(Uuid.parse(it)) },
            privateKey = private_key,
            publicKey = public_key,
        )

    private fun com.moneymanager.database.sql.Api_session_with_latest_import.toApiSession(): ApiSession =
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
