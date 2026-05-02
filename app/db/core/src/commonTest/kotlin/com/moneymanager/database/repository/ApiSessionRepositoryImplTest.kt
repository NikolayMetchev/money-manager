@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.repository

import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.JsonPath
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class ApiSessionRepositoryImplTest : DbTest() {
    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    private val token = "test-token-abc123"

    private fun deviceId() = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-os", "test-machine"))

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
    fun `insertRequest stores method url and headers`() =
        runTest {
            val deviceId = deviceId()
            val sessionId = repositories.apiSessionRepository.createSession(token, deviceId, now, null)
            val headerMap =
                linkedMapOf(
                    "Authorization" to "Bearer token",
                    "Accept" to "application/json",
                )

            val beforeInsert = Clock.System.now()
            val requestId =
                repositories.apiSessionRepository.insertRequest(
                    sessionId = sessionId,
                    method = "GET",
                    url = "https://api.monzo.com/accounts",
                    headers = headerMap,
                )
            val afterInsert = Clock.System.now()

            assertTrue(requestId.id > 0, "Request ID should be positive: $requestId")

            val requests = repositories.apiSessionRepository.getRequestsBySession(sessionId)
            assertEquals(1, requests.size)
            val request = requests.single()
            assertEquals(requestId, request.id)
            assertEquals(sessionId, request.sessionId)
            assertTimestampBetween(request.requestedAt, beforeInsert, afterInsert)
            assertEquals("GET", request.method)
            assertEquals("https://api.monzo.com/accounts", request.url)
            assertContentEquals(headerMap.keys.toList(), request.headers.map { it.key })
            assertContentEquals(headerMap.values.toList(), request.headers.map { it.value })
        }

    @Test
    fun `getRequestsBySession returns newest first`() =
        runTest {
            val deviceId = deviceId()
            val sessionId = repositories.apiSessionRepository.createSession(token, deviceId, now, null)

            repositories.apiSessionRepository.insertRequest(sessionId, "GET", "https://example.test/1", emptyMap())
            repositories.apiSessionRepository.insertRequest(sessionId, "POST", "https://example.test/2", emptyMap())

            val requests = repositories.apiSessionRepository.getRequestsBySession(sessionId)
            assertEquals(2, requests.size)
            assertEquals("POST", requests.first().method)
            assertEquals("https://example.test/2", requests.first().url)
            assertEquals("GET", requests.last().method)
            assertEquals("https://example.test/1", requests.last().url)
        }

    @Test
    fun `insertResponse stores json`() =
        runTest {
            val deviceId = deviceId()
            val sessionId = repositories.apiSessionRepository.createSession(token, deviceId, now, null)
            val responseJson = """{"accounts":[{"id":1}]}"""
            val requestId =
                repositories.apiSessionRepository.insertRequest(
                    sessionId = sessionId,
                    method = "GET",
                    url = "https://example.test/accounts",
                    headers = emptyMap(),
                )

            val beforeInsert = Clock.System.now()
            val responseId =
                repositories.apiSessionRepository.insertResponse(
                    requestId = requestId,
                    sessionId = sessionId,
                    json = responseJson,
                )
            val afterInsert = Clock.System.now()

            assertTrue(responseId.id > 0, "Response ID should be positive: $responseId")

            val responses = repositories.apiSessionRepository.getResponsesBySession(sessionId)
            assertEquals(1, responses.size)
            val response = responses.single()
            assertEquals(responseId, response.id)
            assertEquals(requestId, response.requestId)
            assertEquals(sessionId, response.sessionId)
            assertTimestampBetween(response.respondedAt, beforeInsert, afterInsert)
            assertEquals(responseJson, response.json)
        }

    @Test
    fun `deleteSession cascades to requests headers and responses`() =
        runTest {
            val deviceId = deviceId()
            val sessionId = repositories.apiSessionRepository.createSession(token, deviceId, now, null)

            val requestId =
                repositories.apiSessionRepository.insertRequest(
                    sessionId = sessionId,
                    method = "GET",
                    url = "https://example.test/request",
                    headers = mapOf("Authorization" to "Bearer token"),
                )
            repositories.apiSessionRepository.insertResponse(
                requestId = requestId,
                sessionId = sessionId,
                json = """{"response":true}""",
            )

            repositories.apiSessionRepository.deleteSession(sessionId)

            assertNull(repositories.apiSessionRepository.getSessionById(sessionId))
            assertTrue(repositories.apiSessionRepository.getRequestsBySession(sessionId).isEmpty())
            assertTrue(repositories.apiSessionRepository.getResponsesBySession(sessionId).isEmpty())
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

    @Test
    fun `insertResponseTransaction records an error transaction`() =
        runTest {
            val deviceId = deviceId()
            val sessionId = repositories.apiSessionRepository.createSession(token, deviceId, now, null)
            val requestId =
                repositories.apiSessionRepository.insertRequest(sessionId, "GET", "https://example.test/transactions", emptyMap())
            val responseId =
                repositories.apiSessionRepository.insertResponse(
                    requestId = requestId,
                    sessionId = sessionId,
                    json = """{"transactions":[{"id":"tx_1"},{"id":"tx_2"}]}""",
                )

            val rtId =
                repositories.apiSessionRepository.insertResponseTransaction(
                    responseId = responseId,
                    jsonPath = JsonPath("$.transactions[0]"),
                    state = com.moneymanager.domain.model.ApiResponseTransactionState.ERROR,
                    transactionId = null,
                    errorMessage = "Currency not found",
                )

            assertTrue(rtId.id > 0, "Response transaction ID should be positive")
        }

    @Test
    fun `getResponseTransactions returns all entries for a response`() =
        runTest {
            val deviceId = deviceId()
            val sessionId = repositories.apiSessionRepository.createSession(token, deviceId, now, null)
            val requestId =
                repositories.apiSessionRepository.insertRequest(sessionId, "GET", "https://example.test/transactions", emptyMap())
            val responseId =
                repositories.apiSessionRepository.insertResponse(
                    requestId = requestId,
                    sessionId = sessionId,
                    json = """{"transactions":[{"id":"tx_1"},{"id":"tx_2"}]}""",
                )

            repositories.apiSessionRepository.insertResponseTransaction(
                responseId = responseId,
                jsonPath = JsonPath("$.transactions[0]"),
                state = com.moneymanager.domain.model.ApiResponseTransactionState.ERROR,
                transactionId = null,
                errorMessage = "Currency not found",
            )
            repositories.apiSessionRepository.insertResponseTransaction(
                responseId = responseId,
                jsonPath = JsonPath("$.transactions[1]"),
                state = com.moneymanager.domain.model.ApiResponseTransactionState.ERROR,
                transactionId = null,
                errorMessage = "Currency not found",
            )

            val entries = repositories.apiSessionRepository.getResponseTransactions(responseId)
            assertEquals(2, entries.size)
            assertEquals(JsonPath("$.transactions[0]"), entries[0].jsonPath)
            assertEquals(com.moneymanager.domain.model.ApiResponseTransactionState.ERROR, entries[0].state)
            assertEquals(JsonPath("$.transactions[1]"), entries[1].jsonPath)
            assertEquals(com.moneymanager.domain.model.ApiResponseTransactionState.ERROR, entries[1].state)
        }

    @Test
    fun `getResponseTransactionsBySession returns entries for all session responses`() =
        runTest {
            val deviceId = deviceId()
            val sessionId = repositories.apiSessionRepository.createSession(token, deviceId, now, null)
            val firstRequestId =
                repositories.apiSessionRepository.insertRequest(sessionId, "GET", "https://example.test/transactions?page=1", emptyMap())
            val secondRequestId =
                repositories.apiSessionRepository.insertRequest(sessionId, "GET", "https://example.test/transactions?page=2", emptyMap())
            val firstResponseId =
                repositories.apiSessionRepository.insertResponse(
                    requestId = firstRequestId,
                    sessionId = sessionId,
                    json = """{"transactions":[{"id":"tx_1"}]}""",
                )
            val secondResponseId =
                repositories.apiSessionRepository.insertResponse(
                    requestId = secondRequestId,
                    sessionId = sessionId,
                    json = """{"transactions":[{"id":"tx_2"}]}""",
                )

            repositories.apiSessionRepository.insertResponseTransaction(
                responseId = firstResponseId,
                jsonPath = JsonPath("$.transactions[0]"),
                state = com.moneymanager.domain.model.ApiResponseTransactionState.ERROR,
                transactionId = null,
                errorMessage = "Currency not found",
            )
            repositories.apiSessionRepository.insertResponseTransaction(
                responseId = secondResponseId,
                jsonPath = JsonPath("$.transactions[0]"),
                state = com.moneymanager.domain.model.ApiResponseTransactionState.ERROR,
                transactionId = null,
                errorMessage = "Currency not found",
            )

            val entries = repositories.apiSessionRepository.getResponseTransactionsBySession(sessionId)

            assertEquals(listOf(firstResponseId, secondResponseId), entries.map { it.responseId })
        }

    @Test
    fun `insertResponseTransaction rejects duplicate json path for response`() =
        runTest {
            val deviceId = deviceId()
            val sessionId = repositories.apiSessionRepository.createSession(token, deviceId, now, null)
            val requestId =
                repositories.apiSessionRepository.insertRequest(sessionId, "GET", "https://example.test/transactions", emptyMap())
            val responseId =
                repositories.apiSessionRepository.insertResponse(
                    requestId = requestId,
                    sessionId = sessionId,
                    json = """{"transactions":[{"id":"tx_1"}]}""",
                )

            repositories.apiSessionRepository.insertResponseTransaction(
                responseId = responseId,
                jsonPath = JsonPath("$.transactions[0]"),
                state = com.moneymanager.domain.model.ApiResponseTransactionState.ERROR,
                transactionId = null,
                errorMessage = "Currency not found",
            )

            assertFailsWith<Exception> {
                repositories.apiSessionRepository.insertResponseTransaction(
                    responseId = responseId,
                    jsonPath = JsonPath("$.transactions[0]"),
                    state = com.moneymanager.domain.model.ApiResponseTransactionState.ERROR,
                    transactionId = null,
                    errorMessage = "Duplicate",
                )
            }
        }

    @Test
    fun `insertResponseTransaction rejects imported state without transaction id`() =
        runTest {
            val deviceId = deviceId()
            val sessionId = repositories.apiSessionRepository.createSession(token, deviceId, now, null)
            val requestId =
                repositories.apiSessionRepository.insertRequest(sessionId, "GET", "https://example.test/transactions", emptyMap())
            val responseId =
                repositories.apiSessionRepository.insertResponse(
                    requestId = requestId,
                    sessionId = sessionId,
                    json = """{"transactions":[{"id":"tx_1"}]}""",
                )

            assertFailsWith<Exception> {
                repositories.apiSessionRepository.insertResponseTransaction(
                    responseId = responseId,
                    jsonPath = JsonPath("$.transactions[0]"),
                    state = com.moneymanager.domain.model.ApiResponseTransactionState.IMPORTED,
                    transactionId = null,
                    errorMessage = null,
                )
            }
        }

    @Test
    fun `getResponseTransactions returns empty for response with no entries`() =
        runTest {
            val deviceId = deviceId()
            val sessionId = repositories.apiSessionRepository.createSession(token, deviceId, now, null)
            val requestId =
                repositories.apiSessionRepository.insertRequest(sessionId, "GET", "https://example.test/transactions", emptyMap())
            val responseId =
                repositories.apiSessionRepository.insertResponse(
                    requestId = requestId,
                    sessionId = sessionId,
                    json = """{"transactions":[]}""",
                )

            val entries = repositories.apiSessionRepository.getResponseTransactions(responseId)
            assertTrue(entries.isEmpty())
        }

    @Test
    fun `deleteSession cascades to api_response_transaction`() =
        runTest {
            val deviceId = deviceId()
            val sessionId = repositories.apiSessionRepository.createSession(token, deviceId, now, null)
            val requestId =
                repositories.apiSessionRepository.insertRequest(sessionId, "GET", "https://example.test/transactions", emptyMap())
            val responseId =
                repositories.apiSessionRepository.insertResponse(
                    requestId = requestId,
                    sessionId = sessionId,
                    json = """{"transactions":[{"id":"tx_1"}]}""",
                )
            repositories.apiSessionRepository.insertResponseTransaction(
                responseId = responseId,
                jsonPath = JsonPath("$.transactions[0]"),
                state = com.moneymanager.domain.model.ApiResponseTransactionState.ERROR,
                transactionId = null,
                errorMessage = "Currency not found",
            )

            repositories.apiSessionRepository.deleteSession(sessionId)

            assertNull(repositories.apiSessionRepository.getSessionById(sessionId))
            // After cascading delete, response_transaction records should be gone
            val entries = repositories.apiSessionRepository.getResponseTransactions(responseId)
            assertTrue(entries.isEmpty())
        }

    private fun assertTimestampBetween(
        actual: Instant,
        start: Instant,
        end: Instant,
    ) {
        assertTrue(actual >= start - 1.seconds, "Expected $actual to be at or after $start")
        assertTrue(actual <= end + 1.seconds, "Expected $actual to be at or before $end")
    }
}
