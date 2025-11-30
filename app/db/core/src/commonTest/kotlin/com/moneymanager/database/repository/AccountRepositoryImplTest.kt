@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.repository

import com.moneymanager.database.IN_MEMORY_DATABASE
import com.moneymanager.database.RepositorySet
import com.moneymanager.di.AppComponent
import com.moneymanager.di.createTestAppComponentParams
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.Asset
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.AssetRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class AccountRepositoryImplTest {
    private lateinit var repository: AccountRepository
    private lateinit var assetRepository: AssetRepository
    private lateinit var testAsset: Asset

    @BeforeTest
    fun setup() =
        runTest {
            // Create app component
            val component = AppComponent.create(createTestAppComponentParams())
            val databaseManager = component.databaseManager

            // Open in-memory database for testing
            val database = databaseManager.openDatabase(IN_MEMORY_DATABASE)
            val repositories = RepositorySet(database)

            repository = repositories.accountRepository
            assetRepository = repositories.assetRepository

            // Create a test asset for use in tests
            val assetId = assetRepository.upsertAssetByName("USD")
            testAsset = Asset(id = assetId, name = "USD")
        }

    // CREATE ACCOUNT TESTS

    @Test
    fun `createAccount should insert account and return generated id`() =
        runTest {
            val now = Clock.System.now()
            val account =
                Account(
                    name = "Test Checking",
                    asset = testAsset,
                    initialBalance = 1000.0,
                    openingDate = now,
                )

            val accountId = repository.createAccount(account)

            assertTrue(accountId > 0, "Generated ID should be positive")

            val retrieved = repository.getAccountById(accountId).first()
            assertNotNull(retrieved)
            assertEquals(account.name, retrieved.name)
            assertEquals(account.asset, retrieved.asset)
            assertEquals(account.initialBalance, retrieved.initialBalance)
        }

    @Test
    fun `createAccount should handle different assets`() =
        runTest {
            val now = Clock.System.now()
            val assetNames = listOf("USD", "EUR", "GBP", "JPY", "BTC", "ETH")

            assetNames.forEach { assetName ->
                val assetId = assetRepository.upsertAssetByName(assetName)
                val asset = Asset(id = assetId, name = assetName)
                val account =
                    Account(
                        name = "Test Account",
                        asset = asset,
                        initialBalance = 0.0,
                        openingDate = now,
                    )

                val accountId = repository.createAccount(account)
                val retrieved = repository.getAccountById(accountId).first()

                assertNotNull(retrieved)
                assertEquals(asset, retrieved.asset)
            }
        }

    @Test
    fun `createAccount should handle negative initial balance`() =
        runTest {
            val now = Clock.System.now()
            val account =
                Account(
                    name = "Credit Card",
                    asset = testAsset,
                    initialBalance = -500.0,
                    openingDate = now,
                )

            val accountId = repository.createAccount(account)
            val retrieved = repository.getAccountById(accountId).first()

            assertNotNull(retrieved)
            assertEquals(-500.0, retrieved.initialBalance)
        }

    @Test
    fun `createAccount should handle zero balance`() =
        runTest {
            val now = Clock.System.now()
            val account =
                Account(
                    name = "New Account",
                    asset = testAsset,
                    initialBalance = 0.0,
                    openingDate = now,
                )

            val accountId = repository.createAccount(account)
            val retrieved = repository.getAccountById(accountId).first()

            assertNotNull(retrieved)
            assertEquals(0.0, retrieved.initialBalance)
        }

    // GET ALL ACCOUNTS TESTS

    @Test
    fun `getAllAccounts should return empty list when no accounts exist`() =
        runTest {
            val accounts = repository.getAllAccounts().first()
            assertTrue(accounts.isEmpty())
        }

    @Test
    fun `getAllAccounts should return all accounts`() =
        runTest {
            val now = Clock.System.now()
            val eurAssetId = assetRepository.upsertAssetByName("EUR")
            val eurAsset = Asset(id = eurAssetId, name = "EUR")

            val account1 =
                Account(
                    name = "Account 1",
                    asset = testAsset,
                    initialBalance = 100.0,
                    openingDate = now,
                )
            val account2 =
                Account(
                    name = "Account 2",
                    asset = eurAsset,
                    initialBalance = 200.0,
                    openingDate = now,
                )

            repository.createAccount(account1)
            repository.createAccount(account2)

            val accounts = repository.getAllAccounts().first()
            assertEquals(2, accounts.size)
        }

    // GET ACCOUNT BY ID TESTS

    @Test
    fun `getAccountById should return null for non-existent id`() =
        runTest {
            val account = repository.getAccountById(999L).first()
            assertNull(account)
        }

    @Test
    fun `getAccountById should return correct account`() =
        runTest {
            val now = Clock.System.now()
            val account =
                Account(
                    name = "Test Account",
                    asset = testAsset,
                    initialBalance = 100.0,
                    openingDate = now,
                )

            val accountId = repository.createAccount(account)
            val retrieved = repository.getAccountById(accountId).first()

            assertNotNull(retrieved)
            assertEquals(accountId, retrieved.id)
            assertEquals(account.name, retrieved.name)
        }

    // UPDATE ACCOUNT TESTS

    @Test
    fun `updateAccount should modify existing account`() =
        runTest {
            val now = Clock.System.now()
            val account =
                Account(
                    name = "Original Name",
                    asset = testAsset,
                    initialBalance = 100.0,
                    openingDate = now,
                )

            val accountId = repository.createAccount(account)
            val retrieved = repository.getAccountById(accountId).first()!!

            val eurAssetId = assetRepository.upsertAssetByName("EUR")
            val eurAsset = Asset(id = eurAssetId, name = "EUR")

            val updated =
                retrieved.copy(
                    name = "Updated Name",
                    asset = eurAsset,
                    initialBalance = 200.0,
                )

            repository.updateAccount(updated)
            val result = repository.getAccountById(accountId).first()

            assertNotNull(result)
            assertEquals("Updated Name", result.name)
            assertEquals(eurAsset, result.asset)
            assertEquals(200.0, result.initialBalance)
        }

    // DELETE ACCOUNT TESTS

    @Test
    fun `deleteAccount should remove account from database`() =
        runTest {
            val now = Clock.System.now()
            val account =
                Account(
                    name = "Test Account",
                    asset = testAsset,
                    initialBalance = 100.0,
                    openingDate = now,
                )

            val accountId = repository.createAccount(account)
            repository.deleteAccount(accountId)

            val retrieved = repository.getAccountById(accountId).first()
            assertNull(retrieved)
        }

    @Test
    fun `deleteAccount should not affect other accounts`() =
        runTest {
            val now = Clock.System.now()
            val account1 =
                Account(
                    name = "Account 1",
                    asset = testAsset,
                    initialBalance = 100.0,
                    openingDate = now,
                )
            val account2 =
                Account(
                    name = "Account 2",
                    asset = testAsset,
                    initialBalance = 200.0,
                    openingDate = now,
                )

            val accountId1 = repository.createAccount(account1)
            val accountId2 = repository.createAccount(account2)

            repository.deleteAccount(accountId1)

            val retrieved1 = repository.getAccountById(accountId1).first()
            val retrieved2 = repository.getAccountById(accountId2).first()

            assertNull(retrieved1)
            assertNotNull(retrieved2)
        }

    @Test
    fun `deleteAccount should succeed for non-existent id`() =
        runTest {
            // Should not throw an exception
            repository.deleteAccount(999L)
        }

    // FLOW BEHAVIOR TESTS

    @Test
    fun `getAllAccounts flow should emit updated list after creation`() =
        runTest {
            val now = Clock.System.now()

            // Initially empty
            val initial = repository.getAllAccounts().first()
            assertTrue(initial.isEmpty())

            // Create account
            val account =
                Account(
                    name = "New Account",
                    asset = testAsset,
                    initialBalance = 100.0,
                    openingDate = now,
                )
            repository.createAccount(account)

            // Should now contain the account
            val updated = repository.getAllAccounts().first()
            assertEquals(1, updated.size)
        }

    // EDGE CASES AND INTEGRATION TESTS

    @Test
    fun `should handle large number of accounts`() =
        runTest {
            val now = Clock.System.now()
            val accountCount = 100

            repeat(accountCount) { index ->
                val account =
                    Account(
                        name = "Account $index",
                        asset = testAsset,
                        initialBalance = index * 100.0,
                        openingDate = now,
                    )
                repository.createAccount(account)
            }

            val accounts = repository.getAllAccounts().first()
            assertEquals(accountCount, accounts.size)
        }

    @Test
    fun `should handle accounts with special characters in name`() =
        runTest {
            val now = Clock.System.now()
            val specialNames =
                listOf(
                    "Account with 'quotes'",
                    "Account with \"double quotes\"",
                    "Account with émojis 💰",
                    "Account with ñ and ü",
                    "Account with \$pecial char$",
                )

            specialNames.forEach { name ->
                val account =
                    Account(
                        name = name,
                        asset = testAsset,
                        initialBalance = 100.0,
                        openingDate = now,
                    )

                val accountId = repository.createAccount(account)
                val retrieved = repository.getAccountById(accountId).first()

                assertNotNull(retrieved)
                assertEquals(name, retrieved.name)
            }
        }

    @Test
    fun `should preserve precision for balance values`() =
        runTest {
            val now = Clock.System.now()
            val preciseBalance = 123.45
            val account =
                Account(
                    name = "Test Account",
                    asset = testAsset,
                    initialBalance = preciseBalance,
                    openingDate = now,
                )

            val accountId = repository.createAccount(account)
            val retrieved = repository.getAccountById(accountId).first()

            assertNotNull(retrieved)
            assertEquals(preciseBalance, retrieved.initialBalance)
        }

    @Test
    fun `should handle very large balance values`() =
        runTest {
            val now = Clock.System.now()
            val largeBalance = 999999999.99
            val account =
                Account(
                    name = "Rich Account",
                    asset = testAsset,
                    initialBalance = largeBalance,
                    openingDate = now,
                )

            val accountId = repository.createAccount(account)
            val retrieved = repository.getAccountById(accountId).first()

            assertNotNull(retrieved)
            assertEquals(largeBalance, retrieved.initialBalance)
        }

    @Test
    fun `should maintain account order consistency`() =
        runTest {
            val now = Clock.System.now()
            val accountIds = mutableListOf<Long>()

            repeat(5) { index ->
                val account =
                    Account(
                        name = "Account $index",
                        asset = testAsset,
                        initialBalance = 100.0,
                        openingDate = now,
                    )
                accountIds.add(repository.createAccount(account))
            }

            val accounts = repository.getAllAccounts().first()
            assertEquals(5, accounts.size)

            // Verify all created accounts are present
            accountIds.forEach { id ->
                assertTrue(accounts.any { it.id == id })
            }
        }

    // FOREIGN KEY CONSTRAINT TESTS

    @Test
    fun `createAccount should fail with invalid asset ID of 0`() =
        runTest {
            val now = Clock.System.now()
            val invalidAsset = Asset(id = 0L, name = "Invalid")
            val account =
                Account(
                    name = "Test Account",
                    asset = invalidAsset,
                    initialBalance = 100.0,
                    openingDate = now,
                )

            try {
                repository.createAccount(account)
                throw AssertionError("Expected foreign key constraint violation but account was created")
            } catch (e: Exception) {
                // Expected: Should throw exception due to foreign key constraint
                assertTrue(
                    e.message?.contains("foreign key", ignoreCase = true) == true ||
                        e.message?.contains("constraint", ignoreCase = true) == true,
                    "Expected foreign key constraint error but got: ${e.message}",
                )
            }
        }

    @Test
    fun `createAccount should fail with non-existent asset ID`() =
        runTest {
            val now = Clock.System.now()
            val nonExistentAsset = Asset(id = 999L, name = "Non-existent")
            val account =
                Account(
                    name = "Test Account",
                    asset = nonExistentAsset,
                    initialBalance = 100.0,
                    openingDate = now,
                )

            try {
                repository.createAccount(account)
                throw AssertionError("Expected foreign key constraint violation but account was created")
            } catch (e: Exception) {
                // Expected: Should throw exception due to foreign key constraint
                assertTrue(
                    e.message?.contains("foreign key", ignoreCase = true) == true ||
                        e.message?.contains("constraint", ignoreCase = true) == true,
                    "Expected foreign key constraint error but got: ${e.message}",
                )
            }
        }

    @Test
    fun `updateAccount should fail with invalid asset ID`() =
        runTest {
            // Given: Create a valid account first
            val now = Clock.System.now()
            val validAccount =
                Account(
                    name = "Valid Account",
                    asset = testAsset,
                    initialBalance = 100.0,
                    openingDate = now,
                )
            val accountId = repository.createAccount(validAccount)
            val retrieved = repository.getAccountById(accountId).first()
            assertNotNull(retrieved)

            // When: Try to update with an invalid asset
            val invalidAsset = Asset(id = 999L, name = "Invalid")
            val updatedAccount = retrieved.copy(asset = invalidAsset)

            try {
                repository.updateAccount(updatedAccount)
                throw AssertionError("Expected foreign key constraint violation but account was updated")
            } catch (e: Exception) {
                // Expected: Should throw exception due to foreign key constraint
                assertTrue(
                    e.message?.contains("foreign key", ignoreCase = true) == true ||
                        e.message?.contains("constraint", ignoreCase = true) == true,
                    "Expected foreign key constraint error but got: ${e.message}",
                )
            }
        }
}
