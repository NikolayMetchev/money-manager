package com.moneymanager.apiimporter

import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.ApiSession
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.tasks.Progress
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A bulk API re-import gives each session its own slice of one bar (see `PhasedProgressTest` for the
 * scaling itself).
 */
class ApiImportProgressTest {
    private class NoopMaintenance : Maintenance {
        override suspend fun reindex(): Duration = Duration.ZERO

        override suspend fun vacuum(): Duration = Duration.ZERO

        override suspend fun analyze(): Duration = Duration.ZERO

        override suspend fun refreshMaterializedViews(): Duration = Duration.ZERO

        override suspend fun fullRefreshMaterializedViews(): Duration = Duration.ZERO
    }

    private fun session(id: Long) =
        ApiSession(
            id = ApiSessionId(id),
            deviceId = DeviceId(1),
            createdAt = Instant.fromEpochMilliseconds(id),
            expiresAt = null,
            credentialId = null,
        )

    @Test
    fun `a bulk run scales each session into its own slice and labels it`() =
        runTest {
            val emitted = mutableListOf<Progress>()

            reimportSessionsResiliently(
                sessions = (1L..2L).map { session(it) },
                maintenance = NoopMaintenance(),
                onProgress = { emitted += it },
            ) { _, sessionProgress ->
                sessionProgress?.invoke(Progress("Re-importing", fraction = 0.5f))
            }

            assertEquals(
                listOf("Session 1 of 2", "Session 1 of 2: Re-importing", "Session 2 of 2", "Session 2 of 2: Re-importing"),
                emitted.map { it.detail },
            )
            assertEquals(listOf(0f, 0.25f, 0.5f, 0.75f), emitted.map { it.fraction })
            assertTrue(emitted.all { it.total == 2 }, "the session counter rides along for the caller to show")
        }
}
