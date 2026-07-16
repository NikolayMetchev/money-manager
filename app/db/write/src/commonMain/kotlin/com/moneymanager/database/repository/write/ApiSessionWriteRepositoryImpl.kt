@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.repository.write

import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.ApiImportStrategyId
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiResponseId
import com.moneymanager.domain.model.ApiResponseTransactionId
import com.moneymanager.domain.model.ApiResponseTransactionInsert
import com.moneymanager.domain.model.ApiResponseTransactionState
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.JsonPath
import com.moneymanager.domain.model.MonzoCredentialId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.ApiSessionReadRepository
import com.moneymanager.domain.repository.write.ApiSessionWriteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Instant

class ApiSessionWriteRepositoryImpl(
    database: MoneyManagerDatabaseWrapper,
    reader: ApiSessionReadRepository,
) : ApiSessionWriteRepository,
    ApiSessionReadRepository by reader {
    private val writeQueries = database.apiSessionWriteQueries

    override suspend fun createCredential(
        token: String,
        createdAt: Instant,
        strategyId: ApiImportStrategyId?,
        privateKey: String?,
        publicKey: String?,
        apiSecret: String?,
    ): MonzoCredentialId =
        withContext(Dispatchers.Default) {
            val id =
                writeQueries.transactionWithResult {
                    writeQueries.insertCredential(
                        token = token,
                        created_at = createdAt.toEpochMilliseconds(),
                        strategy_id = strategyId?.id?.toString(),
                        private_key = privateKey,
                        public_key = publicKey,
                        api_secret = apiSecret,
                    )
                    writeQueries.lastInsertCredentialRowId().executeAsOne()
                }
            MonzoCredentialId(id)
        }

    override suspend fun updateCredentialStrategy(
        credentialId: MonzoCredentialId,
        strategyId: ApiImportStrategyId?,
    ): Unit =
        withContext(Dispatchers.Default) {
            val affected =
                writeQueries
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
                writeQueries
                    .updateCredentialKeys(
                        private_key = privateKey,
                        public_key = publicKey,
                        id = credentialId.id,
                    ).await()
            check(affected == 1L) { "Expected to update one credential ($credentialId) keys, but $affected rows matched" }
        }

    override suspend fun updateCredentialSecrets(
        credentialId: MonzoCredentialId,
        token: String,
        apiSecret: String?,
    ): Unit =
        withContext(Dispatchers.Default) {
            val affected =
                writeQueries
                    .updateCredentialSecrets(
                        token = token,
                        api_secret = apiSecret,
                        id = credentialId.id,
                    ).await()
            check(affected == 1L) { "Expected to update one credential ($credentialId) secrets, but $affected rows matched" }
        }

    override suspend fun createSession(
        token: String,
        deviceId: DeviceId,
        createdAt: Instant,
        expiresAt: Instant?,
        credentialId: MonzoCredentialId?,
    ): ApiSessionId =
        withContext(Dispatchers.Default) {
            val id =
                writeQueries.transactionWithResult {
                    writeQueries.insert(
                        token = token,
                        device_id = deviceId.id,
                        created_at = createdAt.toEpochMilliseconds(),
                        expires_at = expiresAt?.toEpochMilliseconds(),
                        credential_id = credentialId?.id,
                    )
                    writeQueries.lastInsertRowId().executeAsOne()
                }
            ApiSessionId(id)
        }

    override suspend fun insertRequest(
        sessionId: ApiSessionId,
        method: String,
        url: String,
        headers: Map<String, String>,
    ): ApiRequestId =
        withContext(Dispatchers.Default) {
            val id =
                writeQueries.transactionWithResult {
                    writeQueries.insertRequest(
                        session_id = sessionId.id,
                        method = method,
                        url = url,
                    )
                    val requestId = writeQueries.lastInsertRowId().executeAsOne()

                    headers.forEach { (key, value) ->
                        writeQueries.insertRequestHeader(
                            request_id = requestId,
                            key = key,
                            value_ = value,
                        )
                    }

                    requestId
                }
            ApiRequestId(id)
        }

    override suspend fun insertResponse(
        requestId: ApiRequestId,
        sessionId: ApiSessionId,
        json: String,
    ): ApiResponseId =
        withContext(Dispatchers.Default) {
            val id =
                writeQueries.transactionWithResult {
                    writeQueries.insertResponse(
                        request_id = requestId.id,
                        session_id = sessionId.id,
                        json = json,
                    )
                    writeQueries.lastInsertRowId().executeAsOne()
                }
            ApiResponseId(id)
        }

    override suspend fun deleteSession(id: ApiSessionId): Unit =
        withContext(Dispatchers.Default) {
            writeQueries.delete(id.id)
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
                writeQueries.transactionWithResult {
                    writeQueries.insertResponseTransaction(
                        response_id = responseId.id,
                        json_path = jsonPath.value,
                        state = state.id.toLong(),
                        transaction_id = transactionId?.id,
                        error_message = errorMessage,
                    )
                    writeQueries.lastInsertApiResponseTransactionId().executeAsOne()
                }
            ApiResponseTransactionId(id)
        }

    override suspend fun insertResponseTransactions(transactions: List<ApiResponseTransactionInsert>): Unit =
        withContext(Dispatchers.Default) {
            if (transactions.isEmpty()) return@withContext

            writeQueries.transaction {
                transactions.forEach { transaction ->
                    writeQueries.insertResponseTransaction(
                        response_id = transaction.responseId.id,
                        json_path = transaction.jsonPath.value,
                        state = transaction.state.id.toLong(),
                        transaction_id = transaction.transactionId?.id,
                        error_message = transaction.errorMessage,
                    )
                }
            }
        }

    override suspend fun markSessionImported(
        id: ApiSessionId,
        revisionId: Long,
        importedAt: Instant,
        importDurationMillis: Long?,
    ): Long =
        withContext(Dispatchers.Default) {
            writeQueries
                .markSessionImported(
                    session_id = id.id,
                    revision_id = revisionId,
                    imported_at = importedAt.toEpochMilliseconds(),
                    import_duration_millis = importDurationMillis,
                ).await()
        }
}
