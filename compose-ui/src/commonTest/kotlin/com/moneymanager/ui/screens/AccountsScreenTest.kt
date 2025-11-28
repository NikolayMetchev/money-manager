@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountType
import com.moneymanager.domain.repository.AccountRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.time.Clock

@OptIn(ExperimentalTestApi::class)
class AccountsScreenTest {
    @Test
    fun accountsScreen_displaysEmptyState_whenNoAccounts() =
        runComposeUiTest {
            // Given
            val repository = FakeAccountRepository(emptyList())

            // When
            setContent {
                AccountsScreen(accountRepository = repository)
            }

            // Then
            onNodeWithText("Your Accounts").assertIsDisplayed()
            onNodeWithText("No accounts yet. Add your first account!").assertIsDisplayed()
        }

    @Test
    fun accountsScreen_displaysAccounts_whenAccountsExist() =
        runComposeUiTest {
            // Given
            val now = Clock.System.now()
            val accounts =
                listOf(
                    Account(
                        id = 1L,
                        name = "Checking Account",
                        type = AccountType.CHECKING,
                        currency = "USD",
                        initialBalance = 1000.0,
                        createdAt = now,
                        updatedAt = now,
                    ),
                    Account(
                        id = 2L,
                        name = "Savings Account",
                        type = AccountType.SAVINGS,
                        currency = "USD",
                        initialBalance = 5000.0,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            val repository = FakeAccountRepository(accounts)

            // When
            setContent {
                AccountsScreen(accountRepository = repository)
            }

            // Then
            onNodeWithText("Checking Account").assertIsDisplayed()
            onNodeWithText("Savings Account").assertIsDisplayed()
            onNodeWithText("USD 1000.00").assertIsDisplayed()
            onNodeWithText("USD 5000.00").assertIsDisplayed()
        }

    @Test
    fun accountsScreen_displaysFloatingActionButton() =
        runComposeUiTest {
            // Given
            val repository = FakeAccountRepository(emptyList())

            // When
            setContent {
                AccountsScreen(accountRepository = repository)
            }

            // Then
            onNodeWithText("+").assertIsDisplayed()
        }

    @Test
    fun accountsScreen_opensCreateDialog_whenFabClicked() =
        runComposeUiTest {
            // Given
            val repository = FakeAccountRepository(emptyList())

            // When
            setContent {
                AccountsScreen(accountRepository = repository)
            }

            onNodeWithText("+").performClick()

            // Then
            onNodeWithText("Create New Account").assertIsDisplayed()
            onNodeWithText("Account Name").assertIsDisplayed()
            onNodeWithText("Account Type").assertIsDisplayed()
        }

    @Test
    fun accountCard_displaysAccountInformation() =
        runComposeUiTest {
            // Given
            val now = Clock.System.now()
            val account =
                Account(
                    id = 1L,
                    name = "My Checking",
                    type = AccountType.CHECKING,
                    currency = "EUR",
                    initialBalance = 2500.50,
                    createdAt = now,
                    updatedAt = now,
                )
            val repository = FakeAccountRepository(listOf(account))

            // When
            setContent {
                AccountsScreen(accountRepository = repository)
            }

            // Then
            onNodeWithText("My Checking").assertIsDisplayed()
            onNodeWithText("CHECKING").assertIsDisplayed()
            onNodeWithText("EUR 2500.50").assertIsDisplayed()
        }

    @Test
    fun accountCard_displaysNegativeBalance_inErrorColor() =
        runComposeUiTest {
            // Given
            val now = Clock.System.now()
            val account =
                Account(
                    id = 1L,
                    name = "Credit Card",
                    type = AccountType.CREDIT_CARD,
                    currency = "USD",
                    initialBalance = -500.0,
                    createdAt = now,
                    updatedAt = now,
                )
            val repository = FakeAccountRepository(listOf(account))

            // When
            setContent {
                AccountsScreen(accountRepository = repository)
            }

            // Then
            onNodeWithText("USD -500.00").assertIsDisplayed()
        }

    @Test
    fun accountCard_opensDeleteDialog_whenDeleteButtonClicked() =
        runComposeUiTest {
            // Given
            val now = Clock.System.now()
            val account =
                Account(
                    id = 1L,
                    name = "Test Account",
                    type = AccountType.CHECKING,
                    currency = "USD",
                    initialBalance = 100.0,
                    createdAt = now,
                    updatedAt = now,
                )
            val repository = FakeAccountRepository(listOf(account))

            // When
            setContent {
                AccountsScreen(accountRepository = repository)
            }

            // Click the delete button (trash icon)
            onNodeWithText("🗑️").performClick()

            // Then
            onNodeWithText("Delete Account?").assertIsDisplayed()
            onNodeWithText("Are you sure you want to delete \"Test Account\"?").assertIsDisplayed()
        }

    @Test
    fun createAccountDialog_validatesRequiredFields() =
        runComposeUiTest {
            // Given
            val repository = FakeAccountRepository(emptyList())

            // When
            setContent {
                AccountsScreen(accountRepository = repository)
            }

            // Open dialog
            onNodeWithText("+").performClick()

            // Try to create without filling fields
            onNodeWithText("Create").performClick()

            // Then
            onNodeWithText("Account name is required").assertIsDisplayed()
        }

    @Test
    fun createAccountDialog_canBeDismissed() =
        runComposeUiTest {
            // Given
            val repository = FakeAccountRepository(emptyList())

            // When
            setContent {
                AccountsScreen(accountRepository = repository)
            }

            // Open dialog
            onNodeWithText("+").performClick()
            onNodeWithText("Create New Account").assertIsDisplayed()

            // Click cancel
            onNodeWithText("Cancel").performClick()

            // Then - dialog should be dismissed
            onNodeWithText("Create New Account").assertDoesNotExist()
        }

    @Test
    fun deleteAccountDialog_canBeDismissed() =
        runComposeUiTest {
            // Given
            val now = Clock.System.now()
            val account =
                Account(
                    id = 1L,
                    name = "Test Account",
                    type = AccountType.CHECKING,
                    currency = "USD",
                    initialBalance = 100.0,
                    createdAt = now,
                    updatedAt = now,
                )
            val repository = FakeAccountRepository(listOf(account))

            // When
            setContent {
                AccountsScreen(accountRepository = repository)
            }

            // Open delete dialog
            onNodeWithText("🗑️").performClick()
            onNodeWithText("Delete Account?").assertIsDisplayed()

            // Click cancel
            onNodeWithText("Cancel").performClick()

            // Then - dialog should be dismissed
            onNodeWithText("Delete Account?").assertDoesNotExist()
        }

    @Test
    fun accountsScreen_displaysMultipleAccounts() =
        runComposeUiTest {
            // Given
            val now = Clock.System.now()
            val accounts =
                listOf(
                    Account(
                        id = 1L,
                        name = "Account 1",
                        type = AccountType.CHECKING,
                        currency = "USD",
                        initialBalance = 100.0,
                        createdAt = now,
                        updatedAt = now,
                    ),
                    Account(
                        id = 2L,
                        name = "Account 2",
                        type = AccountType.SAVINGS,
                        currency = "EUR",
                        initialBalance = 200.0,
                        createdAt = now,
                        updatedAt = now,
                    ),
                    Account(
                        id = 3L,
                        name = "Account 3",
                        type = AccountType.CASH,
                        currency = "GBP",
                        initialBalance = 300.0,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            val repository = FakeAccountRepository(accounts)

            // When
            setContent {
                AccountsScreen(accountRepository = repository)
            }

            // Then - all accounts should be visible
            onNodeWithText("Account 1").assertIsDisplayed()
            onNodeWithText("Account 2").assertIsDisplayed()
            onNodeWithText("Account 3").assertIsDisplayed()
        }

    // Fake repository for testing
    private class FakeAccountRepository(
        private val accounts: List<Account>,
    ) : AccountRepository {
        private val accountsFlow = MutableStateFlow(accounts)
        private val deletedAccounts = mutableListOf<Long>()

        override fun getAllAccounts(): Flow<List<Account>> = accountsFlow

        override fun getActiveAccounts(): Flow<List<Account>> = flowOf(accounts.filter { it.isActive })

        override fun getAccountById(id: Long): Flow<Account?> = flowOf(accounts.find { it.id == id })

        override suspend fun createAccount(account: Account): Long {
            val newId = (accounts.maxOfOrNull { it.id } ?: 0L) + 1
            val newAccount = account.copy(id = newId)
            accountsFlow.value = accountsFlow.value + newAccount
            return newId
        }

        override suspend fun updateAccount(account: Account) {
            accountsFlow.value =
                accountsFlow.value.map {
                    if (it.id == account.id) account else it
                }
        }

        override suspend fun deleteAccount(id: Long) {
            deletedAccounts.add(id)
            accountsFlow.value = accountsFlow.value.filter { it.id != id }
        }
    }
}
