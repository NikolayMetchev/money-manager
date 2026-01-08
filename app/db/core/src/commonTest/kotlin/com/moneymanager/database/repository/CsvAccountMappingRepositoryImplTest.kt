@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.repository

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.FieldMappingId
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedCurrencyMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.test.database.DbTest
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
 * Tests for CsvAccountMappingRepositoryImpl.
 *
 * Tests CRUD operations and cascade delete behavior when strategies or accounts are deleted.
 */
class CsvAccountMappingRepositoryImplTest : DbTest() {
    private suspend fun createTestStrategy(): CsvImportStrategy {
        val now = Clock.System.now()
        val strategyId = CsvImportStrategyId(Uuid.random())

        // Create required currency first
        val currency = repositories.currencyRepository.getAllCurrencies().first().first()

        // Create accounts that the strategy references
        val sourceAccount =
            Account(
                id = AccountId(0),
                name = "Source Account ${Uuid.random()}",
                openingDate = now,
            )
        val sourceAccountId = repositories.accountRepository.createAccount(sourceAccount)

        val targetAccount =
            Account(
                id = AccountId(0),
                name = "Target Account ${Uuid.random()}",
                openingDate = now,
            )
        val targetAccountId = repositories.accountRepository.createAccount(targetAccount)

        val strategy =
            CsvImportStrategy(
                id = strategyId,
                name = "Test Strategy ${Uuid.random()}",
                identificationColumns = setOf("Date", "Amount"),
                fieldMappings =
                    mapOf(
                        TransferField.SOURCE_ACCOUNT to
                            HardCodedAccountMapping(
                                id = FieldMappingId(Uuid.random()),
                                fieldType = TransferField.SOURCE_ACCOUNT,
                                accountId = sourceAccountId,
                            ),
                        TransferField.TARGET_ACCOUNT to
                            HardCodedAccountMapping(
                                id = FieldMappingId(Uuid.random()),
                                fieldType = TransferField.TARGET_ACCOUNT,
                                accountId = targetAccountId,
                            ),
                        TransferField.TIMESTAMP to
                            DateTimeParsingMapping(
                                id = FieldMappingId(Uuid.random()),
                                fieldType = TransferField.TIMESTAMP,
                                dateColumnName = "Date",
                                dateFormat = "dd/MM/yyyy",
                            ),
                        TransferField.DESCRIPTION to
                            DirectColumnMapping(
                                id = FieldMappingId(Uuid.random()),
                                fieldType = TransferField.DESCRIPTION,
                                columnName = "Description",
                            ),
                        TransferField.AMOUNT to
                            AmountParsingMapping(
                                id = FieldMappingId(Uuid.random()),
                                fieldType = TransferField.AMOUNT,
                                mode = AmountMode.SINGLE_COLUMN,
                                amountColumnName = "Amount",
                            ),
                        TransferField.CURRENCY to
                            HardCodedCurrencyMapping(
                                id = FieldMappingId(Uuid.random()),
                                fieldType = TransferField.CURRENCY,
                                currencyId = currency.id,
                            ),
                    ),
                createdAt = now,
                updatedAt = now,
            )

        // Actually persist the strategy to the database
        repositories.csvImportStrategyRepository.createStrategy(strategy)
        return strategy
    }

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

    // ============= CRUD Operations =============

    @Test
    fun `createMapping should insert mapping and be retrievable by strategy`() =
        runTest {
            val strategy = createTestStrategy()
            val account = createTestAccount()

            repositories.csvAccountMappingRepository.createMapping(
                strategyId = strategy.id,
                columnName = "Payee",
                valuePattern = Regex("^Test Pattern$", RegexOption.IGNORE_CASE),
                accountId = account.id,
            )

            val mappings = repositories.csvAccountMappingRepository.getMappingsForStrategy(strategy.id).first()
            assertEquals(1, mappings.size)
            assertEquals(strategy.id, mappings[0].strategyId)
            assertEquals("Payee", mappings[0].columnName)
            assertEquals("^Test Pattern$", mappings[0].valuePattern.pattern)
            assertEquals(account.id, mappings[0].accountId)
        }

    @Test
    fun `getMappingsForStrategy should return all mappings for a strategy ordered by id`() =
        runTest {
            val strategy = createTestStrategy()
            val account1 = createTestAccount()
            val account2 = createTestAccount()

            // Create mappings
            repositories.csvAccountMappingRepository.createMapping(
                strategyId = strategy.id,
                columnName = "Payee",
                valuePattern = Regex("pattern1"),
                accountId = account1.id,
            )
            repositories.csvAccountMappingRepository.createMapping(
                strategyId = strategy.id,
                columnName = "Payee",
                valuePattern = Regex("pattern2"),
                accountId = account2.id,
            )

            val mappings = repositories.csvAccountMappingRepository.getMappingsForStrategy(strategy.id).first()

            assertEquals(2, mappings.size)
            // Should be ordered by id (first created first)
            assertEquals("pattern1", mappings[0].valuePattern.pattern)
            assertEquals("pattern2", mappings[1].valuePattern.pattern)
        }

    @Test
    fun `updateMapping should update mapping fields`() =
        runTest {
            val strategy = createTestStrategy()
            val account1 = createTestAccount()
            val account2 = createTestAccount()

            repositories.csvAccountMappingRepository.createMapping(
                strategyId = strategy.id,
                columnName = "Payee",
                valuePattern = Regex("original"),
                accountId = account1.id,
            )

            val mappings = repositories.csvAccountMappingRepository.getMappingsForStrategy(strategy.id).first()
            assertEquals(1, mappings.size)
            val original = mappings[0]

            // Update the mapping
            val updated =
                original.copy(
                    columnName = "Name",
                    valuePattern = Regex("updated"),
                    accountId = account2.id,
                )
            repositories.csvAccountMappingRepository.updateMapping(updated)

            val result = repositories.csvAccountMappingRepository.getMappingById(original.id).first()
            assertNotNull(result)
            assertEquals("Name", result.columnName)
            assertEquals("updated", result.valuePattern.pattern)
            assertEquals(account2.id, result.accountId)
            // updatedAt should be different
            assertTrue(result.updatedAt > original.updatedAt)
        }

    @Test
    fun `deleteMapping should remove mapping`() =
        runTest {
            val strategy = createTestStrategy()
            val account = createTestAccount()

            repositories.csvAccountMappingRepository.createMapping(
                strategyId = strategy.id,
                columnName = "Payee",
                valuePattern = Regex("test"),
                accountId = account.id,
            )

            val mappings = repositories.csvAccountMappingRepository.getMappingsForStrategy(strategy.id).first()
            assertEquals(1, mappings.size)
            val mappingId = mappings[0].id

            repositories.csvAccountMappingRepository.deleteMapping(mappingId)

            val result = repositories.csvAccountMappingRepository.getMappingById(mappingId).first()
            assertNull(result)
        }

    @Test
    fun `deleteMappingsForStrategy should remove all mappings for a strategy`() =
        runTest {
            val strategy = createTestStrategy()
            val account = createTestAccount()

            repositories.csvAccountMappingRepository.createMapping(
                strategyId = strategy.id,
                columnName = "Payee",
                valuePattern = Regex("pattern1"),
                accountId = account.id,
            )
            repositories.csvAccountMappingRepository.createMapping(
                strategyId = strategy.id,
                columnName = "Name",
                valuePattern = Regex("pattern2"),
                accountId = account.id,
            )

            repositories.csvAccountMappingRepository.deleteMappingsForStrategy(strategy.id)

            val mappings = repositories.csvAccountMappingRepository.getMappingsForStrategy(strategy.id).first()
            assertTrue(mappings.isEmpty())
        }

    // ============= Cascade Delete Tests =============

    @Test
    fun `mappings should be deleted when strategy is deleted`() =
        runTest {
            val strategy = createTestStrategy()
            val account = createTestAccount()

            repositories.csvAccountMappingRepository.createMapping(
                strategyId = strategy.id,
                columnName = "Payee",
                valuePattern = Regex("test"),
                accountId = account.id,
            )

            // Verify mapping exists
            var mappings = repositories.csvAccountMappingRepository.getMappingsForStrategy(strategy.id).first()
            assertEquals(1, mappings.size)

            // Delete the strategy
            repositories.csvImportStrategyRepository.deleteStrategy(strategy.id)

            // Mapping should be cascade deleted
            mappings = repositories.csvAccountMappingRepository.getMappingsForStrategy(strategy.id).first()
            assertTrue(mappings.isEmpty())
        }

    @Test
    fun `mappings should be deleted when account is deleted`() =
        runTest {
            val strategy = createTestStrategy()
            val account = createTestAccount()

            repositories.csvAccountMappingRepository.createMapping(
                strategyId = strategy.id,
                columnName = "Payee",
                valuePattern = Regex("test"),
                accountId = account.id,
            )

            // Verify mapping exists
            var mappings = repositories.csvAccountMappingRepository.getMappingsForStrategy(strategy.id).first()
            assertEquals(1, mappings.size)

            // Delete the account
            repositories.accountRepository.deleteAccount(account.id)

            // Mapping should be cascade deleted
            mappings = repositories.csvAccountMappingRepository.getMappingsForStrategy(strategy.id).first()
            assertTrue(mappings.isEmpty())
        }

    // ============= Regex Pattern Tests =============

    @Test
    fun `stored regex pattern preserves original pattern string`() =
        runTest {
            val strategy = createTestStrategy()
            val account = createTestAccount()

            val complexPattern = "^Nikolay Metchev & Olga Zakharenko$"
            repositories.csvAccountMappingRepository.createMapping(
                strategyId = strategy.id,
                columnName = "Payee",
                valuePattern = Regex(complexPattern),
                accountId = account.id,
            )

            val mappings = repositories.csvAccountMappingRepository.getMappingsForStrategy(strategy.id).first()
            assertEquals(1, mappings.size)
            assertEquals(complexPattern, mappings[0].valuePattern.pattern)
        }

    @Test
    fun `retrieved regex is case insensitive`() =
        runTest {
            val strategy = createTestStrategy()
            val account = createTestAccount()

            repositories.csvAccountMappingRepository.createMapping(
                strategyId = strategy.id,
                columnName = "Payee",
                valuePattern = Regex("paxos"),
                accountId = account.id,
            )

            val mappings = repositories.csvAccountMappingRepository.getMappingsForStrategy(strategy.id).first()
            assertEquals(1, mappings.size)
            val mapping = mappings[0]
            // Should match case-insensitively
            assertTrue(mapping.valuePattern.containsMatchIn("PAXOS"))
            assertTrue(mapping.valuePattern.containsMatchIn("Paxos"))
            assertTrue(mapping.valuePattern.containsMatchIn("paxos"))
        }

    // ============= Edge Cases =============

    @Test
    fun `getMappingsForStrategy returns empty list for strategy without mappings`() =
        runTest {
            val strategy = createTestStrategy()

            val mappings = repositories.csvAccountMappingRepository.getMappingsForStrategy(strategy.id).first()

            assertTrue(mappings.isEmpty())
        }

    @Test
    fun `multiple mappings for same column with different patterns are allowed`() =
        runTest {
            val strategy = createTestStrategy()
            val account1 = createTestAccount()
            val account2 = createTestAccount()

            // Create two mappings for same column, different patterns
            repositories.csvAccountMappingRepository.createMapping(
                strategyId = strategy.id,
                columnName = "Payee",
                valuePattern = Regex("pattern1"),
                accountId = account1.id,
            )
            repositories.csvAccountMappingRepository.createMapping(
                strategyId = strategy.id,
                columnName = "Payee",
                valuePattern = Regex("pattern2"),
                accountId = account2.id,
            )

            val mappings = repositories.csvAccountMappingRepository.getMappingsForStrategy(strategy.id).first()
            assertEquals(2, mappings.size)
        }
}
