@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.repository

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class AccountRepositoryImplTest : DbTest() {
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

            val accountId = repositories.accountRepository.createAccount(account)

            assertTrue(accountId.id > 0, "Generated ID should be positive")

            val retrieved = repositories.accountRepository.getAccountById(accountId).first()
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

                val accountId = repositories.accountRepository.createAccount(account)
                val retrieved = repositories.accountRepository.getAccountById(accountId).first()

                assertNotNull(retrieved)
                assertEquals(name, retrieved.name)
            }

            val allAccounts = repositories.accountRepository.getAllAccounts().first()
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
            val accountId = repositories.accountRepository.createAccount(account)
            val retrieved = repositories.accountRepository.getAccountById(accountId).first()
            assertNotNull(retrieved)

            val updatedAccount = retrieved.copy(name = "Updated Name")
            repositories.accountRepository.updateAccount(updatedAccount)

            val updated = repositories.accountRepository.getAccountById(accountId).first()
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
            val accountId = repositories.accountRepository.createAccount(account)

            repositories.accountRepository.deleteAccount(accountId)

            val deleted = repositories.accountRepository.getAccountById(accountId).first()
            assertEquals(null, deleted)
        }
}
