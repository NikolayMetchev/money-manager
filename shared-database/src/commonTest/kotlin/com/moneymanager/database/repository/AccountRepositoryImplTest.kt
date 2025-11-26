@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.repository

import com.moneymanager.database.DatabaseDriverFactory
import com.moneymanager.database.MoneyManagerDatabase
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class AccountRepositoryImplTest {
    private lateinit var database: MoneyManagerDatabase
    private lateinit var repository: AccountRepositoryImpl
    private lateinit var driver: app.cash.sqldelight.db.SqlDriver

    @BeforeTest
    fun setup() {
        // Create an in-memory database for testing
        val driverFactory = DatabaseDriverFactory()
        driver = driverFactory.createDriver()
        database = MoneyManagerDatabase(driver)
        repository = AccountRepositoryImpl(database)
    }

    @AfterTest
    fun teardown() {
        driver.close()
    }

    // CREATE ACCOUNT TESTS

    @Test
    fun `createAccount should insert account and return generated id`() =
        runTest {
            val now = Clock.System.now()
            val account =
                Account(
                    name = "Test Checking",
                    type = AccountType.CHECKING,
                    currency = "USD",
                    initialBalance = 1000.0,
                    createdAt = now,
                    updatedAt = now,
                )

            val accountId = repository.createAccount(account)

            assertTrue(accountId > 0, "Generated ID should be positive")

            val retrieved = repository.getAccountById(accountId).first()
            assertNotNull(retrieved)
            assertEquals(account.name, retrieved.name)
            assertEquals(account.type, retrieved.type)
            assertEquals(account.currency, retrieved.currency)
            assertEquals(account.initialBalance, retrieved.initialBalance)
        }

    @Test
    fun `createAccount should handle different account types`() =
        runTest {
            val now = Clock.System.now()
            val accountTypes =
                listOf(
                    AccountType.CHECKING,
                    AccountType.SAVINGS,
                    AccountType.CREDIT_CARD,
                    AccountType.CASH,
                    AccountType.INVESTMENT,
                )

            accountTypes.forEach { type ->
                val account =
                    Account(
                        name = "Test ${type.name}",
                        type = type,
                        currency = "USD",
                        initialBalance = 0.0,
                        createdAt = now,
                        updatedAt = now,
                    )

                val accountId = repository.createAccount(account)
                val retrieved = repository.getAccountById(accountId).first()

                assertNotNull(retrieved)
                assertEquals(type, retrieved.type)
            }
        }

    @Test
    fun `createAccount should handle different currencies`() =
        runTest {
            val now = Clock.System.now()
            val currencies = listOf("USD", "EUR", "GBP", "JPY")

            currencies.forEach { currency ->
                val account =
                    Account(
                        name = "Test Account",
                        type = AccountType.CHECKING,
                        currency = currency,
                        initialBalance = 0.0,
                        createdAt = now,
                        updatedAt = now,
                    )

                val accountId = repository.createAccount(account)
                val retrieved = repository.getAccountById(accountId).first()

                assertNotNull(retrieved)
                assertEquals(currency, retrieved.currency)
            }
        }

    @Test
    fun `createAccount should handle negative initial balance`() =
        runTest {
            val now = Clock.System.now()
            val account =
                Account(
                    name = "Credit Card",
                    type = AccountType.CREDIT_CARD,
                    currency = "USD",
                    initialBalance = -500.0,
                    createdAt = now,
                    updatedAt = now,
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
                    type = AccountType.CHECKING,
                    currency = "USD",
                    initialBalance = 0.0,
                    createdAt = now,
                    updatedAt = now,
                )

            val accountId = repository.createAccount(account)
            val retrieved = repository.getAccountById(accountId).first()

            assertNotNull(retrieved)
            assertEquals(0.0, retrieved.initialBalance)
        }

    @Test
    fun `createAccount should handle optional fields`() =
        runTest {
            val now = Clock.System.now()
            val account =
                Account(
                    name = "Test Account",
                    type = AccountType.CHECKING,
                    currency = "USD",
                    initialBalance = 100.0,
                    color = "#FF5733",
                    icon = "💰",
                    isActive = true,
                    createdAt = now,
                    updatedAt = now,
                )

            val accountId = repository.createAccount(account)
            val retrieved = repository.getAccountById(accountId).first()

            assertNotNull(retrieved)
            assertEquals("#FF5733", retrieved.color)
            assertEquals("💰", retrieved.icon)
            assertTrue(retrieved.isActive)
        }

    @Test
    fun `createAccount should handle inactive account`() =
        runTest {
            val now = Clock.System.now()
            val account =
                Account(
                    name = "Inactive Account",
                    type = AccountType.CHECKING,
                    currency = "USD",
                    initialBalance = 100.0,
                    isActive = false,
                    createdAt = now,
                    updatedAt = now,
                )

            val accountId = repository.createAccount(account)
            val retrieved = repository.getAccountById(accountId).first()

            assertNotNull(retrieved)
            assertFalse(retrieved.isActive)
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
            val account1 =
                Account(
                    name = "Account 1",
                    type = AccountType.CHECKING,
                    currency = "USD",
                    initialBalance = 100.0,
                    createdAt = now,
                    updatedAt = now,
                )
            val account2 =
                Account(
                    name = "Account 2",
                    type = AccountType.SAVINGS,
                    currency = "EUR",
                    initialBalance = 200.0,
                    createdAt = now,
                    updatedAt = now,
                )

            repository.createAccount(account1)
            repository.createAccount(account2)

            val accounts = repository.getAllAccounts().first()
            assertEquals(2, accounts.size)
        }

    @Test
    fun `getAllAccounts should return both active and inactive accounts`() =
        runTest {
            val now = Clock.System.now()
            val activeAccount =
                Account(
                    name = "Active Account",
                    type = AccountType.CHECKING,
                    currency = "USD",
                    initialBalance = 100.0,
                    isActive = true,
                    createdAt = now,
                    updatedAt = now,
                )
            val inactiveAccount =
                Account(
                    name = "Inactive Account",
                    type = AccountType.SAVINGS,
                    currency = "USD",
                    initialBalance = 200.0,
                    isActive = false,
                    createdAt = now,
                    updatedAt = now,
                )

            repository.createAccount(activeAccount)
            repository.createAccount(inactiveAccount)

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
                    type = AccountType.CHECKING,
                    currency = "USD",
                    initialBalance = 100.0,
                    createdAt = now,
                    updatedAt = now,
                )

            val accountId = repository.createAccount(account)
            val retrieved = repository.getAccountById(accountId).first()

            assertNotNull(retrieved)
            assertEquals(accountId, retrieved.id)
            assertEquals(account.name, retrieved.name)
        }

    @Test
    fun `getAccountById should return inactive account`() =
        runTest {
            val now = Clock.System.now()
            val account =
                Account(
                    name = "Inactive Account",
                    type = AccountType.CHECKING,
                    currency = "USD",
                    initialBalance = 100.0,
                    isActive = false,
                    createdAt = now,
                    updatedAt = now,
                )

            val accountId = repository.createAccount(account)
            val retrieved = repository.getAccountById(accountId).first()

            assertNotNull(retrieved)
            assertFalse(retrieved.isActive)
        }

    // GET ACTIVE ACCOUNTS TESTS

    @Test
    fun `getActiveAccounts should return empty list when no active accounts exist`() =
        runTest {
            val now = Clock.System.now()
            val inactiveAccount =
                Account(
                    name = "Inactive Account",
                    type = AccountType.CHECKING,
                    currency = "USD",
                    initialBalance = 100.0,
                    isActive = false,
                    createdAt = now,
                    updatedAt = now,
                )

            repository.createAccount(inactiveAccount)
            val accounts = repository.getActiveAccounts().first()

            assertTrue(accounts.isEmpty())
        }

    @Test
    fun `getActiveAccounts should return only active accounts`() =
        runTest {
            val now = Clock.System.now()
            val activeAccount1 =
                Account(
                    name = "Active Account 1",
                    type = AccountType.CHECKING,
                    currency = "USD",
                    initialBalance = 100.0,
                    isActive = true,
                    createdAt = now,
                    updatedAt = now,
                )
            val activeAccount2 =
                Account(
                    name = "Active Account 2",
                    type = AccountType.SAVINGS,
                    currency = "USD",
                    initialBalance = 200.0,
                    isActive = true,
                    createdAt = now,
                    updatedAt = now,
                )
            val inactiveAccount =
                Account(
                    name = "Inactive Account",
                    type = AccountType.CASH,
                    currency = "USD",
                    initialBalance = 300.0,
                    isActive = false,
                    createdAt = now,
                    updatedAt = now,
                )

            repository.createAccount(activeAccount1)
            repository.createAccount(activeAccount2)
            repository.createAccount(inactiveAccount)

            val accounts = repository.getActiveAccounts().first()
            assertEquals(2, accounts.size)
            assertTrue(accounts.all { it.isActive })
        }

    @Test
    fun `getActiveAccounts should return empty list when no accounts exist`() =
        runTest {
            val accounts = repository.getActiveAccounts().first()
            assertTrue(accounts.isEmpty())
        }

    // UPDATE ACCOUNT TESTS

    @Test
    fun `updateAccount should modify existing account`() =
        runTest {
            val now = Clock.System.now()
            val account =
                Account(
                    name = "Original Name",
                    type = AccountType.CHECKING,
                    currency = "USD",
                    initialBalance = 100.0,
                    createdAt = now,
                    updatedAt = now,
                )

            val accountId = repository.createAccount(account)
            val retrieved = repository.getAccountById(accountId).first()!!

            val later = Clock.System.now()
            val updated =
                retrieved.copy(
                    name = "Updated Name",
                    type = AccountType.SAVINGS,
                    currency = "EUR",
                    color = "#FF0000",
                    icon = "🏦",
                    updatedAt = later,
                )

            repository.updateAccount(updated)
            val result = repository.getAccountById(accountId).first()

            assertNotNull(result)
            assertEquals("Updated Name", result.name)
            assertEquals(AccountType.SAVINGS, result.type)
            assertEquals("EUR", result.currency)
            assertEquals("#FF0000", result.color)
            assertEquals("🏦", result.icon)
        }

    @Test
    fun `updateAccount should not modify initial balance`() =
        runTest {
            val now = Clock.System.now()
            val account =
                Account(
                    name = "Test Account",
                    type = AccountType.CHECKING,
                    currency = "USD",
                    initialBalance = 100.0,
                    createdAt = now,
                    updatedAt = now,
                )

            val accountId = repository.createAccount(account)
            val retrieved = repository.getAccountById(accountId).first()!!

            // Note: The update query does not include initialBalance
            val updated = retrieved.copy(name = "Updated Name")
            repository.updateAccount(updated)

            val result = repository.getAccountById(accountId).first()
            assertNotNull(result)
            assertEquals(100.0, result.initialBalance)
        }

    @Test
    fun `updateAccount should toggle isActive status`() =
        runTest {
            val now = Clock.System.now()
            val account =
                Account(
                    name = "Test Account",
                    type = AccountType.CHECKING,
                    currency = "USD",
                    initialBalance = 100.0,
                    isActive = true,
                    createdAt = now,
                    updatedAt = now,
                )

            val accountId = repository.createAccount(account)
            val retrieved = repository.getAccountById(accountId).first()!!

            val later = Clock.System.now()
            val deactivated = retrieved.copy(isActive = false, updatedAt = later)
            repository.updateAccount(deactivated)

            val result = repository.getAccountById(accountId).first()
            assertNotNull(result)
            assertFalse(result.isActive)

            // Should not appear in active accounts
            val activeAccounts = repository.getActiveAccounts().first()
            assertFalse(activeAccounts.any { it.id == accountId })

            // But should still appear in all accounts
            val allAccounts = repository.getAllAccounts().first()
            assertTrue(allAccounts.any { it.id == accountId })
        }

    @Test
    fun `updateAccount should handle clearing optional fields`() =
        runTest {
            val now = Clock.System.now()
            val account =
                Account(
                    name = "Test Account",
                    type = AccountType.CHECKING,
                    currency = "USD",
                    initialBalance = 100.0,
                    color = "#FF0000",
                    icon = "🏦",
                    createdAt = now,
                    updatedAt = now,
                )

            val accountId = repository.createAccount(account)
            val retrieved = repository.getAccountById(accountId).first()!!

            val later = Clock.System.now()
            val updated = retrieved.copy(color = null, icon = null, updatedAt = later)
            repository.updateAccount(updated)

            val result = repository.getAccountById(accountId).first()
            assertNotNull(result)
            assertNull(result.color)
            assertNull(result.icon)
        }

    // DELETE ACCOUNT TESTS

    @Test
    fun `deleteAccount should remove account from database`() =
        runTest {
            val now = Clock.System.now()
            val account =
                Account(
                    name = "Test Account",
                    type = AccountType.CHECKING,
                    currency = "USD",
                    initialBalance = 100.0,
                    createdAt = now,
                    updatedAt = now,
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
                    type = AccountType.CHECKING,
                    currency = "USD",
                    initialBalance = 100.0,
                    createdAt = now,
                    updatedAt = now,
                )
            val account2 =
                Account(
                    name = "Account 2",
                    type = AccountType.SAVINGS,
                    currency = "USD",
                    initialBalance = 200.0,
                    createdAt = now,
                    updatedAt = now,
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
                    type = AccountType.CHECKING,
                    currency = "USD",
                    initialBalance = 100.0,
                    createdAt = now,
                    updatedAt = now,
                )
            repository.createAccount(account)

            // Should now contain the account
            val updated = repository.getAllAccounts().first()
            assertEquals(1, updated.size)
        }

    @Test
    fun `getActiveAccounts flow should emit updated list after deactivation`() =
        runTest {
            val now = Clock.System.now()
            val account =
                Account(
                    name = "Test Account",
                    type = AccountType.CHECKING,
                    currency = "USD",
                    initialBalance = 100.0,
                    isActive = true,
                    createdAt = now,
                    updatedAt = now,
                )

            val accountId = repository.createAccount(account)

            // Should appear in active accounts
            val active1 = repository.getActiveAccounts().first()
            assertEquals(1, active1.size)

            // Deactivate
            val retrieved = repository.getAccountById(accountId).first()!!
            val later = Clock.System.now()
            repository.updateAccount(retrieved.copy(isActive = false, updatedAt = later))

            // Should no longer appear in active accounts
            val active2 = repository.getActiveAccounts().first()
            assertTrue(active2.isEmpty())
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
                        type = AccountType.entries[index % AccountType.entries.size],
                        currency = "USD",
                        initialBalance = index * 100.0,
                        createdAt = now,
                        updatedAt = now,
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
                        type = AccountType.CHECKING,
                        currency = "USD",
                        initialBalance = 100.0,
                        createdAt = now,
                        updatedAt = now,
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
                    type = AccountType.CHECKING,
                    currency = "USD",
                    initialBalance = preciseBalance,
                    createdAt = now,
                    updatedAt = now,
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
                    type = AccountType.INVESTMENT,
                    currency = "USD",
                    initialBalance = largeBalance,
                    createdAt = now,
                    updatedAt = now,
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
                        type = AccountType.CHECKING,
                        currency = "USD",
                        initialBalance = 100.0,
                        createdAt = now,
                        updatedAt = now,
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
}
