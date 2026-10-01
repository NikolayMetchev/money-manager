package com.moneymanager.database.repository

import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.importengineapi.ensureApiCredentials
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.time.Instant

/**
 * An API strategy holds at most one connection row, and asking for it again returns the same row — the rules
 * the connections checklist relies on to reconnect strategies from the credential vault without duplicates.
 */
class ApiCredentialConnectionTest : DbTest() {
    override val installBuiltInStrategies: Boolean = true

    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private suspend fun strategies(): List<ApiImportStrategy> = repositories.apiImportStrategyRepository.getAllStrategies().first()

    @Test
    fun `ensuring a connection twice returns the same row`() =
        runTest {
            val strategy = strategies().first()

            val first = repositories.apiSessionRepository.ensureCredential(strategy.id, now)
            val second = repositories.apiSessionRepository.ensureCredential(strategy.id, now)

            assertEquals(first, second)
            assertEquals(1, repositories.apiSessionRepository.getAllCredentials().size)
        }

    @Test
    fun `different strategies each get their own connection`() =
        runTest {
            val (first, second) = strategies().take(2)

            val ids = repositories.importEngine.ensureApiCredentials(listOf(first.id, second.id), now)

            assertNotEquals(ids.getValue(first.id), ids.getValue(second.id))
            val byStrategy = repositories.apiSessionRepository.getAllCredentials().associateBy { it.strategyId }
            assertEquals(ids.getValue(first.id), byStrategy[first.id]?.id)
            assertEquals(ids.getValue(second.id), byStrategy[second.id]?.id)
        }

    @Test
    fun `the credentials flow reflects a newly connected api`() =
        runTest {
            val strategy = strategies().first()
            assertEquals(emptyList(), repositories.apiSessionRepository.getCredentialsFlow().first())

            repositories.importEngine.ensureApiCredentials(listOf(strategy.id), now)

            val credential =
                repositories.apiSessionRepository
                    .getCredentialsFlow()
                    .first()
                    .singleOrNull()
            assertNotNull(credential)
            assertEquals(strategy.id, credential.strategyId)
        }
}
