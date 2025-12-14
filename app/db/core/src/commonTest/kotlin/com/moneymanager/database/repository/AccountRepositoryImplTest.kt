@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.repository

import com.moneymanager.database.RepositorySet
import com.moneymanager.database.createTestDatabaseLocation
import com.moneymanager.database.deleteTestDatabase
import com.moneymanager.di.AppComponent
import com.moneymanager.di.createTestAppComponentParams
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.repository.AccountRepository
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
            val repositories = RepositorySet(database, database)

            repository = repositories.accountRepository
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
                    id = AccountId(0),
                    name = "Test Checking",
                    openingDate = now,
                )

            val accountId = repository.createAccount(account)

            assertTrue(accountId.id > 0, "Generated ID should be positive")

            val retrieved = repository.getAccountById(accountId).first()
            assertNotNull(retrieved)
            assertEquals(account.name, retrieved.name)
        }

    @Test
    fun `createAccount should create multiple accounts`() =
        runTest {
            val now = Clock.System.now()
            val accountNames = listOf("Checking", "Savings", "Credit Card", "Investment")

            accountNames.forEach { name ->
                val account =
                    Account(
                        id = AccountId(0),
                        name = name,
                        openingDate = now,
                    )

                val accountId = repository.createAccount(account)
                val retrieved = repository.getAccountById(accountId).first()

                assertNotNull(retrieved)
                assertEquals(name, retrieved.name)
            }

            val allAccounts = repository.getAllAccounts().first()
            assertEquals(accountNames.size, allAccounts.size)
        }

    @Test
    fun `updateAccount should update account name`() =
        runTest {
            val now = Clock.System.now()
            val account =
                Account(
                    id = AccountId(0),
                    name = "Original Name",
                    openingDate = now,
                )
            val accountId = repository.createAccount(account)
            val retrieved = repository.getAccountById(accountId).first()
            assertNotNull(retrieved)

            val updatedAccount = retrieved.copy(name = "Updated Name")
            repository.updateAccount(updatedAccount)

            val updated = repository.getAccountById(accountId).first()
            assertNotNull(updated)
            assertEquals("Updated Name", updated.name)
        }

    @Test
    fun `deleteAccount should remove account`() =
        runTest {
            val now = Clock.System.now()
            val account =
                Account(
                    id = AccountId(0),
                    name = "To Delete",
                    openingDate = now,
                )
            val accountId = repository.createAccount(account)

            repository.deleteAccount(accountId)

            val deleted = repository.getAccountById(accountId).first()
            assertEquals(null, deleted)
        }
}
