@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.repository
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.createAccount
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Tests for AccountMappingRepositoryImpl.
 *
 * A mapping is global (strategyId null) or scoped to a CSV import strategy. Tests CRUD, scope
 * round-tripping, and cascade delete when the referenced account is deleted.
 */
class AccountMappingRepositoryImplTest : DbTest() {
    private suspend fun createTestAccount(): Account {
        val now = Clock.System.now()
        val account =
            Account(
                id = AccountId(0),
                name = "Test Account ${Uuid.random()}",
                openingDate = now,
            )
        val accountId = repositories.accountRepository.createAccount(account)
        return repositories.accountRepository.getAccountById(accountId).first()!!
    }

    private suspend fun createTestStrategy(): CsvImportStrategyId {
        val id = CsvImportStrategyId(Uuid.random())
        repositories.csvImportStrategyRepository.createStrategy(
            CsvImportStrategy(
                id = id,
                name = "Strategy ${Uuid.random()}",
                identificationColumns = setOf("Date"),
                fieldMappings = emptyMap(),
                createdAt = Clock.System.now(),
                updatedAt = Clock.System.now(),
            ),
            Source.Manual,
        )
        return id
    }

    @Test
    fun `global and strategy-scoped mappings coexist and round-trip their scope`() =
        runTest {
            val account = createTestAccount()
            val strategyId = createTestStrategy()

            repositories.accountMappingRepository.createMapping(
                columnName = "Payee",
                valuePattern = Regex("global"),
                accountId = account.id,
                strategyId = null,
            )
            repositories.accountMappingRepository.createMapping(
                columnName = "Payee",
                valuePattern = Regex("scoped"),
                accountId = account.id,
                strategyId = strategyId,
            )

            val mappings = repositories.accountMappingRepository.getAllMappings().first()
            assertEquals(null, mappings.single { it.valuePattern.pattern == "global" }.strategyId)
            assertEquals(strategyId, mappings.single { it.valuePattern.pattern == "scoped" }.strategyId)
        }

    @Test
    fun `strategy-scoped mappings are cascade-deleted when the strategy is deleted`() =
        runTest {
            val account = createTestAccount()
            val strategyId = createTestStrategy()
            repositories.accountMappingRepository.createMapping(
                columnName = "Payee",
                valuePattern = Regex("scoped"),
                accountId = account.id,
                strategyId = strategyId,
            )

            repositories.csvImportStrategyRepository.deleteStrategy(strategyId)

            assertTrue(
                repositories.accountMappingRepository
                    .getAllMappings()
                    .first()
                    .isEmpty(),
            )
        }

    // ============= CRUD Operations =============

    @Test
    fun `createMapping should insert mapping and be retrievable`() =
        runTest {
            val account = createTestAccount()

            repositories.accountMappingRepository.createMapping(
                columnName = "Payee",
                valuePattern = Regex("^Test Pattern$", RegexOption.IGNORE_CASE),
                accountId = account.id,
            )

            val mappings = repositories.accountMappingRepository.getAllMappings().first()
            assertEquals(1, mappings.size)
            assertEquals("Payee", mappings[0].columnName)
            assertEquals("^Test Pattern$", mappings[0].valuePattern.pattern)
            assertEquals(account.id, mappings[0].accountId)
        }

    @Test
    fun `getAllMappings should return all mappings ordered by id`() =
        runTest {
            val account1 = createTestAccount()
            val account2 = createTestAccount()

            repositories.accountMappingRepository.createMapping(
                columnName = "Payee",
                valuePattern = Regex("pattern1"),
                accountId = account1.id,
            )
            repositories.accountMappingRepository.createMapping(
                columnName = "Payee",
                valuePattern = Regex("pattern2"),
                accountId = account2.id,
            )

            val mappings = repositories.accountMappingRepository.getAllMappings().first()

            assertEquals(2, mappings.size)
            // Should be ordered by id (first created first)
            assertEquals("pattern1", mappings[0].valuePattern.pattern)
            assertEquals("pattern2", mappings[1].valuePattern.pattern)
        }

    @Test
    fun `updateMapping should update mapping fields`() =
        runTest {
            val account1 = createTestAccount()
            val account2 = createTestAccount()

            repositories.accountMappingRepository.createMapping(
                columnName = "Payee",
                valuePattern = Regex("original"),
                accountId = account1.id,
            )

            val mappings = repositories.accountMappingRepository.getAllMappings().first()
            assertEquals(1, mappings.size)
            val original = mappings[0]

            val updated =
                original.copy(
                    columnName = "Name",
                    valuePattern = Regex("updated"),
                    accountId = account2.id,
                )
            repositories.accountMappingRepository.updateMapping(updated)

            val result = repositories.accountMappingRepository.getMappingById(original.id).first()
            assertNotNull(result)
            assertEquals("Name", result.columnName)
            assertEquals("updated", result.valuePattern.pattern)
            assertEquals(account2.id, result.accountId)
            assertTrue(result.updatedAt > original.updatedAt)
        }

    @Test
    fun `deleteMapping should remove mapping`() =
        runTest {
            val account = createTestAccount()

            repositories.accountMappingRepository.createMapping(
                columnName = "Payee",
                valuePattern = Regex("test"),
                accountId = account.id,
            )

            val mappings = repositories.accountMappingRepository.getAllMappings().first()
            assertEquals(1, mappings.size)
            val mappingId = mappings[0].id

            repositories.accountMappingRepository.deleteMapping(mappingId)

            val result = repositories.accountMappingRepository.getMappingById(mappingId).first()
            assertNull(result)
        }

    // ============= Cascade Delete Tests =============

    @Test
    fun `mappings should be deleted when account is deleted`() =
        runTest {
            val account = createTestAccount()

            repositories.accountMappingRepository.createMapping(
                columnName = "Payee",
                valuePattern = Regex("test"),
                accountId = account.id,
            )

            var mappings = repositories.accountMappingRepository.getAllMappings().first()
            assertEquals(1, mappings.size)

            repositories.accountRepository.deleteAccount(account.id)

            mappings = repositories.accountMappingRepository.getAllMappings().first()
            assertTrue(mappings.isEmpty())
        }

    // ============= Regex Pattern Tests =============

    @Test
    fun `stored regex pattern preserves original pattern string`() =
        runTest {
            val account = createTestAccount()

            val complexPattern = "^Nikolay Metchev & Olga Zakharenko$"
            repositories.accountMappingRepository.createMapping(
                columnName = "Payee",
                valuePattern = Regex(complexPattern),
                accountId = account.id,
            )

            val mappings = repositories.accountMappingRepository.getAllMappings().first()
            assertEquals(1, mappings.size)
            assertEquals(complexPattern, mappings[0].valuePattern.pattern)
        }

    @Test
    fun `retrieved regex is case insensitive`() =
        runTest {
            val account = createTestAccount()

            repositories.accountMappingRepository.createMapping(
                columnName = "Payee",
                valuePattern = Regex("paxos"),
                accountId = account.id,
            )

            val mappings = repositories.accountMappingRepository.getAllMappings().first()
            assertEquals(1, mappings.size)
            val mapping = mappings[0]
            assertTrue(mapping.valuePattern.containsMatchIn("PAXOS"))
            assertTrue(mapping.valuePattern.containsMatchIn("Paxos"))
            assertTrue(mapping.valuePattern.containsMatchIn("paxos"))
        }

    // ============= Edge Cases =============

    @Test
    fun `getAllMappings returns empty list when there are no mappings`() =
        runTest {
            val mappings = repositories.accountMappingRepository.getAllMappings().first()
            assertTrue(mappings.isEmpty())
        }

    @Test
    fun `multiple mappings for same column with different patterns are allowed`() =
        runTest {
            val account1 = createTestAccount()
            val account2 = createTestAccount()

            repositories.accountMappingRepository.createMapping(
                columnName = "Payee",
                valuePattern = Regex("pattern1"),
                accountId = account1.id,
            )
            repositories.accountMappingRepository.createMapping(
                columnName = "Payee",
                valuePattern = Regex("pattern2"),
                accountId = account2.id,
            )

            val mappings = repositories.accountMappingRepository.getAllMappings().first()
            assertEquals(2, mappings.size)
        }
}
