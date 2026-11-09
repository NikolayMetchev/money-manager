package com.moneymanager.database

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.moneymanager.database.write.recordSource
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.JsonPath
import com.moneymanager.domain.model.Source
import com.moneymanager.test.database.DbTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.time.Clock

class DbEntitySourceTest : DbTest() {
    @Test
    fun `recordEntityProvenance persists api detail per revision of the same entity`() =
        runDbTest {
            val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-os", "test-machine"))
            val sessionId = repositories.apiSessionRepository.createSession(deviceId, Clock.System.now(), null)
            val requestId =
                repositories.apiSessionRepository.insertRequest(
                    sessionId = sessionId,
                    method = "GET",
                    url = "https://example.test/accounts",
                    headers = emptyMap(),
                )
            val queries = database.entitySourceSelectQueries

            database.recordSource(
                deviceId = deviceId,
                entityType = EntityType.ACCOUNT,
                entityId = 99L,
                revisionId = 1L,
                source = Source.Api(sessionId, requestId, JsonPath("$.accounts[0]")),
            )
            database.recordSource(
                deviceId = deviceId,
                entityType = EntityType.ACCOUNT,
                entityId = 99L,
                revisionId = 2L,
                source = Source.Api(sessionId, requestId, JsonPath("$.accounts[0].updated")),
            )

            val revisionOne =
                queries.selectEntitySourceForRevision(EntityType.ACCOUNT.id, 99L, 1L).awaitAsOneOrNull()
            val revisionTwo =
                queries.selectEntitySourceForRevision(EntityType.ACCOUNT.id, 99L, 2L).awaitAsOneOrNull()

            assertNotNull(revisionOne)
            assertNotNull(revisionTwo)
            assertNotEquals(revisionOne.id, revisionTwo.id)
            assertNotNull(queries.selectApiEntitySourceId(id = revisionOne.id).awaitAsOneOrNull())
            assertNotNull(queries.selectApiEntitySourceId(id = revisionTwo.id).awaitAsOneOrNull())
        }

    @Test
    fun `a statement account is kept only by the record that wrote the revision`() =
        runDbTest {
            val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-os", "test-machine"))
            val sessionId = repositories.apiSessionRepository.createSession(deviceId, Clock.System.now(), null)
            val transfer = 99L
            database.recordSource(deviceId, EntityType.TRANSFER, transfer, 1L, Source.Api(sessionId))
            // A later record of the same revision (a no-op update) keeps the first writer's row, which has no statement account.
            database.recordSource(deviceId, EntityType.TRANSFER, transfer, 1L, Source.Api(sessionId, statementAccountId = AccountId(7)))
            database.recordSource(deviceId, EntityType.TRANSFER, transfer, 2L, Source.Api(sessionId, statementAccountId = AccountId(8)))
            database.recordSource(deviceId, EntityType.TRANSFER, 100L, 1L, Source.Api(sessionId, statementAccountId = AccountId(9)))

            val statementAccounts =
                database.entitySourceSelectQueries
                    .selectStatementAccountsOfTransfers(listOf(transfer, 100L))
                    .awaitAsList()
                    .associate { it.entity_id to it.account_id }
            assertEquals(mapOf(100L to 9L), statementAccounts)
        }
}
