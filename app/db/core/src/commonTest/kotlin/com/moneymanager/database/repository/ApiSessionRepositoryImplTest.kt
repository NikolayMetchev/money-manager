@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.repository

import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class ApiSessionRepositoryImplTest : DbTest() {
    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    private val token = "test-token-abc123"

    private suspend fun deviceId() =
        repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-os", "test-machine"))

    @Test
    fun `createSession returns a positive id`() =
        runTest {
            val deviceId = deviceId()
            val id = repositories.apiSessionRepository.createSession(token, deviceId, now, null)
            assertTrue(id.id > 0, "Session ID should be positive: $id")
        }

    @Test
    fun `getSessionById returns the created session`() =
        runTest {
            val deviceId = deviceId()
            val id = repositories.apiSessionRepository.createSession(token, deviceId, now, null)

            val session = repositories.apiSessionRepository.getSessionById(id)
            assertNotNull(session)
            assertEquals(id, session.id)
            assertEquals(token, session.token)
            assertEquals(deviceId, session.deviceId)
            assertEquals(now, session.createdAt)
            assertNull(session.expiresAt)
            assertNull(session.revokedAt)
        }

    @Test
    fun `getSessionById returns null for unknown id`() =
        runTest {
            val session = repositories.apiSessionRepository.getSessionById(ApiSessionId(999L))
            assertNull(session)
        }

    @Test
    fun `getSessionByToken returns the created session`() =
        runTest {
            val deviceId = deviceId()
            repositories.apiSessionRepository.createSession(token, deviceId, now, null)

            val session = repositories.apiSessionRepository.getSessionByToken(token)
            assertNotNull(session)
            assertEquals(token, session.token)
        }

    @Test
    fun `getSessionByToken returns null for unknown token`() =
        runTest {
            val session = repositories.apiSessionRepository.getSessionByToken("unknown-token")
            assertNull(session)
        }

    @Test
    fun `createSession stores expiresAt correctly`() =
        runTest {
            val deviceId = deviceId()
            val expiresAt = now + 1.hours
            val id = repositories.apiSessionRepository.createSession(token, deviceId, now, expiresAt)

            val session = repositories.apiSessionRepository.getSessionById(id)
            assertNotNull(session)
            assertEquals(expiresAt, session.expiresAt)
        }

    @Test
    fun `getSessionsByDevice returns sessions for that device`() =
        runTest {
            val deviceId = deviceId()
            repositories.apiSessionRepository.createSession("token-1", deviceId, now, null)
            repositories.apiSessionRepository.createSession("token-2", deviceId, now, null)

            val sessions = repositories.apiSessionRepository.getSessionsByDevice(deviceId)
            assertEquals(2, sessions.size)
            assertTrue(sessions.all { it.deviceId == deviceId })
        }

    @Test
    fun `revokeSession sets revokedAt`() =
        runTest {
            val deviceId = deviceId()
            val id = repositories.apiSessionRepository.createSession(token, deviceId, now, null)

            val revokedAt = now + 1.hours
            repositories.apiSessionRepository.revokeSession(id, revokedAt)

            val session = repositories.apiSessionRepository.getSessionById(id)
            assertNotNull(session)
            assertEquals(revokedAt, session.revokedAt)
        }

    @Test
    fun `revokeSessionByToken sets revokedAt`() =
        runTest {
            val deviceId = deviceId()
            val id = repositories.apiSessionRepository.createSession(token, deviceId, now, null)

            val revokedAt = now + 1.hours
            repositories.apiSessionRepository.revokeSessionByToken(token, revokedAt)

            val session = repositories.apiSessionRepository.getSessionById(id)
            assertNotNull(session)
            assertEquals(revokedAt, session.revokedAt)
        }

    @Test
    fun `revokeAllSessionsForDevice revokes all active sessions`() =
        runTest {
            val deviceId = deviceId()
            val id1 = repositories.apiSessionRepository.createSession("token-1", deviceId, now, null)
            val id2 = repositories.apiSessionRepository.createSession("token-2", deviceId, now, null)

            val revokedAt = now + 1.hours
            repositories.apiSessionRepository.revokeAllSessionsForDevice(deviceId, revokedAt)

            val session1 = repositories.apiSessionRepository.getSessionById(id1)
            val session2 = repositories.apiSessionRepository.getSessionById(id2)
            assertNotNull(session1?.revokedAt)
            assertNotNull(session2?.revokedAt)
        }

    @Test
    fun `getActiveSessions excludes revoked sessions`() =
        runTest {
            val deviceId = deviceId()
            val id1 = repositories.apiSessionRepository.createSession("token-1", deviceId, now, null)
            repositories.apiSessionRepository.createSession("token-2", deviceId, now, null)

            repositories.apiSessionRepository.revokeSession(id1, now + 1.hours)

            val active = repositories.apiSessionRepository.getActiveSessions(now + 2.hours)
            assertEquals(1, active.size)
            assertEquals("token-2", active.first().token)
        }

    @Test
    fun `getActiveSessions excludes expired sessions`() =
        runTest {
            val deviceId = deviceId()
            // Session expires in 1 hour
            repositories.apiSessionRepository.createSession("expired-token", deviceId, now, now + 1.hours)
            repositories.apiSessionRepository.createSession("valid-token", deviceId, now, now + 3.hours)

            // Check at now + 2 hours: first session is expired, second is not
            val active = repositories.apiSessionRepository.getActiveSessions(now + 2.hours)
            assertEquals(1, active.size)
            assertEquals("valid-token", active.first().token)
        }

    @Test
    fun `deleteSession removes the session`() =
        runTest {
            val deviceId = deviceId()
            val id = repositories.apiSessionRepository.createSession(token, deviceId, now, null)

            repositories.apiSessionRepository.deleteSession(id)

            val session = repositories.apiSessionRepository.getSessionById(id)
            assertNull(session)
        }
}
