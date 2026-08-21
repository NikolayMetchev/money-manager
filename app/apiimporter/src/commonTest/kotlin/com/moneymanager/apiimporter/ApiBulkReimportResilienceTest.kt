@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.apiimporter

import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.ApiSession
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.DeviceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A bulk re-import mutates the database session by session, so the materialized views are stale the
 * moment the first session's transfers are deleted. Whatever the run does about a failing session,
 * it must not leave the views describing the pre-re-import state (issue #737).
 */
class ApiBulkReimportResilienceTest {
    private class CountingMaintenance : Maintenance {
        var refreshes = 0

        override suspend fun reindex(): Duration = Duration.ZERO

        override suspend fun vacuum(): Duration = Duration.ZERO

        override suspend fun analyze(): Duration = Duration.ZERO

        override suspend fun refreshMaterializedViews(): Duration {
            refreshes++
            return Duration.ZERO
        }

        override suspend fun fullRefreshMaterializedViews(): Duration = Duration.ZERO
    }

    private fun session(id: Long) =
        ApiSession(
            id = ApiSessionId(id),
            token = "token-$id",
            deviceId = DeviceId(1),
            createdAt = Instant.fromEpochMilliseconds(id),
            expiresAt = null,
            credentialId = null,
        )

    @Test
    fun `a failing session does not abort the run and is reported`() =
        runTest {
            val maintenance = CountingMaintenance()
            val attempted = mutableListOf<Long>()

            val result =
                reimportSessionsResiliently(
                    sessions = (1L..4L).map { session(it) },
                    maintenance = maintenance,
                    onProgress = null,
                ) { session ->
                    attempted += session.id.id
                    if (session.id.id == 2L) error("boom")
                }

            assertEquals(listOf(1L, 2L, 3L, 4L), attempted, "a failing session should not stop the ones after it")
            assertEquals(3, result.sessionsReimported, "only the sessions that completed should be counted")
            assertEquals(listOf(2L), result.failures.map { it.sessionId.id })
            assertEquals("boom", result.failures.single().message)
            assertEquals(1, maintenance.refreshes, "views should be refreshed exactly once for the whole run")
        }

    @Test
    fun `views are refreshed even when the run is cancelled part-way`() =
        runTest {
            val maintenance = CountingMaintenance()

            assertFailsWith<CancellationException> {
                reimportSessionsResiliently(
                    sessions = (1L..3L).map { session(it) },
                    maintenance = maintenance,
                    onProgress = null,
                ) { session ->
                    if (session.id.id == 2L) throw CancellationException("cancelled")
                }
            }

            assertEquals(1, maintenance.refreshes, "the sessions already re-imported have invalidated the views")
        }

    @Test
    fun `an empty run still refreshes once`() =
        runTest {
            val maintenance = CountingMaintenance()
            val result = reimportSessionsResiliently(emptyList(), maintenance, onProgress = null) { }
            assertEquals(0, result.sessionsReimported)
            assertEquals(1, maintenance.refreshes)
        }
}
