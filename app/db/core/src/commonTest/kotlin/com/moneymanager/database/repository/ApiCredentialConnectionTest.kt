@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.repository

import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.importengineapi.updateApiCredentialSecrets
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.time.Instant

/**
 * An API strategy holds at most one credential, and that credential is editable in place — the two rules the
 * connections checklist relies on.
 */
class ApiCredentialConnectionTest : DbTest() {
    override val installBuiltInStrategies: Boolean = true

    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private suspend fun strategies(): List<ApiImportStrategy> = repositories.apiImportStrategyRepository.getAllStrategies().first()

    @Test
    fun `a strategy cannot have two credentials`() =
        runTest {
            val strategy = strategies().first()
            repositories.apiSessionRepository.createCredential(
                token = "first-token",
                createdAt = now,
                strategyId = strategy.id,
            )

            assertFails {
                repositories.apiSessionRepository.createCredential(
                    token = "second-token",
                    createdAt = now,
                    strategyId = strategy.id,
                )
            }
        }

    @Test
    fun `different strategies each get their own credential`() =
        runTest {
            val (first, second) = strategies().take(2)

            repositories.apiSessionRepository.createCredential("token-one", now, first.id)
            repositories.apiSessionRepository.createCredential("token-two", now, second.id)

            val byStrategy = repositories.apiSessionRepository.getAllCredentials().associateBy { it.strategyId }
            assertEquals("token-one", byStrategy[first.id]?.token)
            assertEquals("token-two", byStrategy[second.id]?.token)
        }

    @Test
    fun `the import engine replaces a token in place rather than adding a credential`() =
        runTest {
            val strategy = strategies().first { it.name == "Crypto.com Exchange" }
            val id =
                repositories.apiSessionRepository.createCredential(
                    token = "old-key",
                    createdAt = now,
                    strategyId = strategy.id,
                    apiSecret = "old-secret",
                )

            repositories.importEngine.updateApiCredentialSecrets(id, token = "new-key", apiSecret = "new-secret")

            val credentials = repositories.apiSessionRepository.getAllCredentials()
            assertEquals(1, credentials.size)
            val credential = credentials.single()
            assertEquals(id, credential.id)
            assertEquals("new-key", credential.token)
            assertEquals("new-secret", credential.apiSecret)
            assertEquals(strategy.id, credential.strategyId)
        }

    @Test
    fun `the credentials flow reflects a newly connected api`() =
        runTest {
            val strategy = strategies().first()
            assertEquals(emptyList(), repositories.apiSessionRepository.getCredentialsFlow().first())

            repositories.apiSessionRepository.createCredential("a-token", now, strategy.id)

            val credential =
                repositories.apiSessionRepository
                    .getCredentialsFlow()
                    .first()
                    .singleOrNull()
            assertNotNull(credential)
            assertEquals(strategy.id, credential.strategyId)
        }
}
