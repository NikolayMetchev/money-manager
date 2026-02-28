@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.repository

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
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

    @Test
    fun `countTransfersByAccount should return zero for account with no transfers`() =
        runTest {
            val now = Clock.System.now()
            val accountId =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Empty Account", openingDate = now),
                )

            val count = repositories.accountRepository.countTransfersByAccount(accountId)

            assertEquals(0L, count)
        }

    @Test
    fun `countTransfersByAccount should return correct count`() =
        runTest {
            val now = Clock.System.now()
            val sourceAccountId =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Source", openingDate = now),
                )
            val targetAccountId =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Target", openingDate = now),
                )
            val currencyId = repositories.currencyRepository.upsertCurrencyByCode("USD", "US Dollar")
            val currency = repositories.currencyRepository.getCurrencyById(currencyId).first()!!

            createTransfer(
                Transfer(
                    id = TransferId(0L),
                    timestamp = now,
                    description = "Transfer 1",
                    sourceAccountId = sourceAccountId,
                    targetAccountId = targetAccountId,
                    amount = Money.fromDisplayValue("100", currency),
                ),
            )
            createTransfer(
                Transfer(
                    id = TransferId(0L),
                    timestamp = now,
                    description = "Transfer 2",
                    sourceAccountId = sourceAccountId,
                    targetAccountId = targetAccountId,
                    amount = Money.fromDisplayValue("200", currency),
                ),
            )

            val sourceCount = repositories.accountRepository.countTransfersByAccount(sourceAccountId)
            val targetCount = repositories.accountRepository.countTransfersByAccount(targetAccountId)

            assertEquals(2L, sourceCount)
            assertEquals(2L, targetCount)
        }

    @Test
    fun `deleteAccountAndMoveTransactions should move transfers to target account`() =
        runTest {
            val now = Clock.System.now()
            val accountA =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Account A", openingDate = now),
                )
            val accountB =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Account B", openingDate = now),
                )
            val accountC =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Account C", openingDate = now),
                )
            val currencyId = repositories.currencyRepository.upsertCurrencyByCode("USD", "US Dollar")
            val currency = repositories.currencyRepository.getCurrencyById(currencyId).first()!!

            // A -> C transfer (source references account being deleted)
            createTransfer(
                Transfer(
                    id = TransferId(0L),
                    timestamp = now,
                    description = "A to C",
                    sourceAccountId = accountA,
                    targetAccountId = accountC,
                    amount = Money.fromDisplayValue("100", currency),
                ),
            )
            // C -> A transfer (target references account being deleted)
            createTransfer(
                Transfer(
                    id = TransferId(0L),
                    timestamp = now,
                    description = "C to A",
                    sourceAccountId = accountC,
                    targetAccountId = accountA,
                    amount = Money.fromDisplayValue("200", currency),
                ),
            )

            // Delete account A, move transactions to account B
            // (no transfers exist between A and B, so this is valid)
            repositories.accountRepository.deleteAccountAndMoveTransactions(
                accountToDelete = accountA,
                targetAccount = accountB,
            )

            // Account A should be deleted
            val deletedAccount = repositories.accountRepository.getAccountById(accountA).first()
            assertEquals(null, deletedAccount)

            // Transfers should now reference account B instead of A
            val accountBTransfers = repositories.transactionRepository.getTransactionsByAccount(accountB).first()
            assertEquals(2, accountBTransfers.size)
            // A -> C became B -> C
            val movedSource = accountBTransfers.first { it.description == "A to C" }
            assertEquals(accountB, movedSource.sourceAccountId)
            assertEquals(accountC, movedSource.targetAccountId)
            // C -> A became C -> B
            val movedTarget = accountBTransfers.first { it.description == "C to A" }
            assertEquals(accountC, movedTarget.sourceAccountId)
            assertEquals(accountB, movedTarget.targetAccountId)
        }

    @Test
    fun `deleteAccount should still work for accounts without transfers`() =
        runTest {
            val now = Clock.System.now()
            val accountId =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "No Transfers", openingDate = now),
                )

            repositories.accountRepository.deleteAccount(accountId)

            val deleted = repositories.accountRepository.getAccountById(accountId).first()
            assertEquals(null, deleted)
        }
}
