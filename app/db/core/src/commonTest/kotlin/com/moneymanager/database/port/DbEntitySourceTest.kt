@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.port

import com.moneymanager.domain.ApiEntitySourceRecord
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.JsonPath
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.time.Clock

class DbEntitySourceTest : DbTest() {
    private fun deviceId() = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-os", "test-machine"))

    @Test
    fun `recordFromApiBatch persists multiple revisions for the same entity`() =
        runTest {
            val deviceId = deviceId()
            val entitySource = DbEntitySource(database.entitySourceQueries, database.transferSourceQueries, deviceId)
            val sessionId = repositories.apiSessionRepository.createSession("token", deviceId, Clock.System.now(), null)
            val requestId =
                repositories.apiSessionRepository.insertRequest(
                    sessionId = sessionId,
                    method = "GET",
                    url = "https://example.test/accounts",
                    headers = emptyMap(),
                )

            entitySource.recordFromApiBatch(
                listOf(
                    ApiEntitySourceRecord(
                        entityType = EntityType.ACCOUNT,
                        entityId = 99L,
                        revisionId = 1L,
                        sessionId = sessionId,
                        requestId = requestId,
                        jsonPath = JsonPath("$.accounts[0]"),
                    ),
                    ApiEntitySourceRecord(
                        entityType = EntityType.ACCOUNT,
                        entityId = 99L,
                        revisionId = 2L,
                        sessionId = sessionId,
                        requestId = requestId,
                        jsonPath = JsonPath("$.accounts[0].updated"),
                    ),
                ),
            )

            val revisionOne =
                database.entitySourceQueries
                    .selectEntitySourceForRevision(
                        entity_type_id = EntityType.ACCOUNT.id,
                        entity_id = 99L,
                        revision_id = 1L,
                    ).executeAsOneOrNull()
            val revisionTwo =
                database.entitySourceQueries
                    .selectEntitySourceForRevision(
                        entity_type_id = EntityType.ACCOUNT.id,
                        entity_id = 99L,
                        revision_id = 2L,
                    ).executeAsOneOrNull()

            assertNotNull(revisionOne)
            assertNotNull(revisionTwo)
            assertNotEquals(revisionOne.id, revisionTwo.id)
            assertNotNull(database.entitySourceQueries.selectApiEntitySourceId(id = revisionOne.id).executeAsOneOrNull())
            assertNotNull(database.entitySourceQueries.selectApiEntitySourceId(id = revisionTwo.id).executeAsOneOrNull())
        }
}
