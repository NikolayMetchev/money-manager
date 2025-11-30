@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.repository

import com.moneymanager.database.RepositorySet
import com.moneymanager.database.createTestDatabaseLocation
import com.moneymanager.database.deleteTestDatabase
import com.moneymanager.di.AppComponent
import com.moneymanager.di.createTestAppComponentParams
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.Asset
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.AssetRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class AccountRepositoryImplTest {
    private lateinit var repository: AccountRepository
    private lateinit var assetRepository: AssetRepository
    private lateinit var testAsset: Asset
    private lateinit var testDbLocation: com.moneymanager.database.DbLocation

    @BeforeTest
    fun setup() =
        runTest {
            // Create temporary database file
            testDbLocation = createTestDatabaseLocation()

            // Create app component
            val component = AppComponent.create(createTestAppComponentParams())
            val databaseManager = component.databaseManager

            // Open file-based database for testing
            val database = databaseManager.openDatabase(testDbLocation)
            val repositories = RepositorySet(database)

            repository = repositories.accountRepository
            assetRepository = repositories.assetRepository

            // Create a test asset for use in tests
            val assetId = assetRepository.upsertAssetByName("USD")
            testAsset = Asset(id = assetId, name = "USD")
        }

    @AfterTest
    fun cleanup() {
        deleteTestDatabase(testDbLocation)
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
