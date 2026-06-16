@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.port

import com.moneymanager.database.recordSource
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.JsonPath
import com.moneymanager.domain.model.Source
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.time.Clock

class DbEntitySourceTest : DbTest() {
    @Test
    fun `recordEntityProvenance persists api detail per revision of the same entity`() =
        runTest {
            val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-os", "test-machine"))
            val sessionId = repositories.apiSessionRepository.createSession("token", deviceId, Clock.System.now(), null)
            val requestId =
                repositories.apiSessionRepository.insertRequest(
                    sessionId = sessionId,
                    method = "GET",
                    url = "https://example.test/accounts",
                    headers = emptyMap(),
                )
            val queries = database.entitySourceQueries

            queries.recordSource(
                deviceId = deviceId,
                entityType = EntityType.ACCOUNT,
                entityId = 99L,
                revisionId = 1L,
                source = Source.Api(sessionId, requestId, JsonPath("$.accounts[0]")),
            )
            queries.recordSource(
                deviceId = deviceId,
                entityType = EntityType.ACCOUNT,
                entityId = 99L,
                revisionId = 2L,
                source = Source.Api(sessionId, requestId, JsonPath("$.accounts[0].updated")),
            )

            val revisionOne =
                queries.selectEntitySourceForRevision(EntityType.ACCOUNT.id, 99L, 1L).executeAsOneOrNull()
            val revisionTwo =
                queries.selectEntitySourceForRevision(EntityType.ACCOUNT.id, 99L, 2L).executeAsOneOrNull()

            assertNotNull(revisionOne)
            assertNotNull(revisionTwo)
            assertNotEquals(revisionOne.id, revisionTwo.id)
            assertNotNull(queries.selectApiEntitySourceId(id = revisionOne.id).executeAsOneOrNull())
            assertNotNull(queries.selectApiEntitySourceId(id = revisionTwo.id).executeAsOneOrNull())
        }
}
