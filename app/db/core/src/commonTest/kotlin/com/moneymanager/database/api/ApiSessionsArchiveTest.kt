package com.moneymanager.database.api

import com.moneymanager.apiimporter.ApiSessionsArchive
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.test.database.DbTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class ApiSessionsArchiveTest : DbTest() {
    override val installBuiltInStrategies: Boolean = true

    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    private val coveredUntil = Instant.fromEpochMilliseconds(1_699_000_000_000L)

    @Test
    fun `export then import restores sessions once`() =
        runDbTest {
            val sessions = repositories.apiSessionRepository
            val strategies = repositories.apiImportStrategyRepository
            val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-machine", "Test OS"))
            val credentialId = apiConnection("Monzo", now)
            val sessionId = sessions.createSession(deviceId, now, null, credentialId)
            val requestId = sessions.insertRequest(sessionId, "GET", "https://api.example/accounts", mapOf("Accept" to "json"))
            sessions.insertResponse(requestId, sessionId, """{"accounts":[]}""")
            sessions.insertRequest(sessionId, "GET", "https://api.example/unanswered", emptyMap())
            sessions.recordDownloadCoverage(sessionId, "transactions", coveredUntil)

            val exported = ApiSessionsArchive.export(sessions, strategies)

            // Re-importing into the same database changes nothing.
            val again = ApiSessionsArchive.import(exported, deviceId, sessions, strategies, repositories.importEngine)
            assertEquals(0, again.sessionsImported)
            assertEquals(1, again.sessionsAlreadyPresent)

            sessions.deleteSession(sessionId)
            val restored = ApiSessionsArchive.import(exported, deviceId, sessions, strategies, repositories.importEngine)
            assertEquals(1, restored.sessionsImported)
            assertTrue(restored.missingStrategies.isEmpty())

            val session = sessions.getSessionsByCredential(credentialId).single()
            assertEquals(now, session.createdAt)
            val requests = sessions.getRequestsBySession(session.id)
            assertEquals(setOf("https://api.example/accounts", "https://api.example/unanswered"), requests.map { it.url }.toSet())
            assertEquals(
                mapOf("Accept" to "json"),
                requests.single { it.url.endsWith("accounts") }.headers.associate { it.key to it.value },
            )
            assertEquals(listOf("""{"accounts":[]}"""), sessions.getResponsesBySession(session.id).map { it.json })
            assertEquals(mapOf("transactions" to coveredUntil), sessions.getDownloadCoverageBySession(session.id))
        }

    @Test
    fun `unknown strategies are reported and skipped`() =
        runDbTest {
            val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-machine", "Test OS"))
            val content = """{"version":1,"strategies":[{"name":"No Such Strategy","sessions":[]}]}"""
            val result =
                ApiSessionsArchive.import(
                    content,
                    deviceId,
                    repositories.apiSessionRepository,
                    repositories.apiImportStrategyRepository,
                    repositories.importEngine,
                )
            assertEquals(setOf("No Such Strategy"), result.missingStrategies)
            assertEquals(0, result.sessionsImported)
        }

    @Test
    fun `importAndDescribe reports a bad file instead of throwing`() =
        runDbTest {
            val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-machine", "Test OS"))
            val message =
                ApiSessionsArchive.importAndDescribe(
                    "not json",
                    deviceId,
                    repositories.apiSessionRepository,
                    repositories.apiImportStrategyRepository,
                    repositories.importEngine,
                )
            assertTrue(message.startsWith("Failed to import API sessions"), message)
        }

    @Test
    fun `importAndDescribe summarises what happened`() =
        runDbTest {
            val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-machine", "Test OS"))
            val message =
                ApiSessionsArchive.importAndDescribe(
                    """{"version":1,"strategies":[{"name":"No Such Strategy","sessions":[]}]}""",
                    deviceId,
                    repositories.apiSessionRepository,
                    repositories.apiImportStrategyRepository,
                    repositories.importEngine,
                )
            assertEquals("Imported 0 session(s). Skipped sessions for unknown strategies: No Such Strategy", message)
        }

    @Test
    fun `an unsupported version is refused`() =
        runDbTest {
            val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-machine", "Test OS"))
            val message =
                ApiSessionsArchive.importAndDescribe(
                    """{"version":99,"strategies":[]}""",
                    deviceId,
                    repositories.apiSessionRepository,
                    repositories.apiImportStrategyRepository,
                    repositories.importEngine,
                )
            assertTrue("Unsupported" in message, message)
        }

    @Test
    fun `a session that fails partway is removed rather than left half imported`() =
        runDbTest {
            val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-machine", "Test OS"))
            val credentialId = apiConnection("Monzo", now)
            // A blank header name violates the table's CHECK, after the session and a first request exist.
            val content =
                """{"version":1,"strategies":[{"name":"Monzo","sessions":[{"createdAt":1700000000000,"coverage":{},"requests":[
                    {"method":"GET","url":"https://api.example/a","headers":{},"response":null},
                    {"method":"GET","url":"https://api.example/b","headers":{" ":"x"},"response":null}]}]}]}"""
            val message =
                ApiSessionsArchive.importAndDescribe(
                    content,
                    deviceId,
                    repositories.apiSessionRepository,
                    repositories.apiImportStrategyRepository,
                    repositories.importEngine,
                )
            assertTrue(message.startsWith("Failed to import API sessions"), message)
            assertTrue(repositories.apiSessionRepository.getSessionsByCredential(credentialId).isEmpty())
        }
}
