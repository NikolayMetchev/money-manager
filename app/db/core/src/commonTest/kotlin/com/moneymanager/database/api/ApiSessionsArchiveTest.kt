package com.moneymanager.database.api

import com.moneymanager.apiimport.API_SESSIONS_ARCHIVE_IMPORT_TASK_KEY
import com.moneymanager.apiimport.startApiSessionsArchiveImport
import com.moneymanager.apiimporter.ApiSessionsArchive
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.tasks.Progress
import com.moneymanager.tasks.TaskManager
import com.moneymanager.tasks.TaskStatus
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
            val progress = mutableListOf<Progress>()
            val restored =
                ApiSessionsArchive.import(exported, deviceId, sessions, strategies, repositories.importEngine) { progress += it }
            // Counted over the requests written, so the bar fills as each one lands.
            assertEquals(listOf(null, 0, 1, 2), progress.map { it.processed })
            assertEquals(2, progress.last().total)
            assertEquals(1f, progress.last().fraction)
            assertEquals("Restoring Monzo session 1 of 1", progress.last().detail)
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
    fun `an archive import runs as one task at a time and hands back its summary`() =
        runDbTest {
            val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-machine", "Test OS"))
            val manager = TaskManager(backgroundScope)
            val finished = CompletableDeferred<String>()
            val release = CompletableDeferred<Unit>()

            fun start(onFinished: suspend (String) -> Unit) =
                manager.startApiSessionsArchiveImport(
                    """{"version":1,"strategies":[{"name":"No Such Strategy","sessions":[]}]}""",
                    deviceId,
                    repositories.apiSessionRepository,
                    repositories.apiImportStrategyRepository,
                    repositories.importEngine,
                    onFinished,
                )

            assertNotNull(
                start { summary ->
                    release.await()
                    finished.complete(summary)
                },
            )
            assertNull(start {}, "a second import waits for the first")
            release.complete(Unit)

            val summary = "Imported 0 session(s). Skipped sessions for unknown strategies: No Such Strategy"
            assertEquals(summary, finished.await())
            val task =
                manager.tasks
                    .first { tasks -> tasks.any { it.key == API_SESSIONS_ARCHIVE_IMPORT_TASK_KEY && !it.isRunning } }
                    .single { it.key == API_SESSIONS_ARCHIVE_IMPORT_TASK_KEY }
            assertEquals("Import API sessions", task.title)
            assertEquals(TaskStatus.SUCCEEDED, task.status)
            assertEquals(summary, task.progress.detail)
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
