@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.repository

import com.moneymanager.database.sql.MoneyManagerDatabase
import com.moneymanager.domain.model.ApiSession
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.repository.ApiSessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Instant

class ApiSessionRepositoryImpl(
    database: MoneyManagerDatabase,
) : ApiSessionRepository {
    private val queries = database.apiSessionQueries

    override suspend fun createSession(
        token: String,
        deviceId: DeviceId,
        createdAt: Instant,
        expiresAt: Instant?,
    ): ApiSessionId =
        withContext(Dispatchers.Default) {
            val id =
                queries.transactionWithResult {
                    queries.insert(
                        token = token,
                        device_id = deviceId.id,
                        created_at = createdAt.toEpochMilliseconds(),
                        expires_at = expiresAt?.toEpochMilliseconds(),
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

    override suspend fun getActiveSessions(now: Instant): List<ApiSession> =
        withContext(Dispatchers.Default) {
            queries.selectActiveSessions(now.toEpochMilliseconds()).executeAsList().map { it.toApiSession() }
        }

    override suspend fun revokeSession(
        id: ApiSessionId,
        revokedAt: Instant,
    ): Unit =
        withContext(Dispatchers.Default) {
            queries.revoke(
                revoked_at = revokedAt.toEpochMilliseconds(),
                id = id.id,
            )
        }

    override suspend fun revokeSessionByToken(
        token: String,
        revokedAt: Instant,
    ): Unit =
        withContext(Dispatchers.Default) {
            queries.revokeByToken(
                revoked_at = revokedAt.toEpochMilliseconds(),
                token = token,
            )
        }

    override suspend fun revokeAllSessionsForDevice(
        deviceId: DeviceId,
        revokedAt: Instant,
    ): Unit =
        withContext(Dispatchers.Default) {
            queries.revokeAllForDevice(
                revoked_at = revokedAt.toEpochMilliseconds(),
                device_id = deviceId.id,
            )
        }

    override suspend fun deleteSession(id: ApiSessionId): Unit =
        withContext(Dispatchers.Default) {
            queries.delete(id.id)
        }

    private fun com.moneymanager.database.sql.Api_session.toApiSession(): ApiSession =
        ApiSession(
            id = ApiSessionId(id),
            token = token,
            deviceId = DeviceId(device_id),
            createdAt = Instant.fromEpochMilliseconds(created_at),
            expiresAt = expires_at?.let { Instant.fromEpochMilliseconds(it) },
            revokedAt = revoked_at?.let { Instant.fromEpochMilliseconds(it) },
        )
}
