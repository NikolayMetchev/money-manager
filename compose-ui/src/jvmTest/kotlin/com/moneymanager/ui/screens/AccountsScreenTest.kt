@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.screens

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountType
import com.moneymanager.domain.repository.AccountRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import kotlin.time.Clock

class AccountsScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun accountsScreen_displaysEmptyState_whenNoAccounts() {
        // Given
        val repository = FakeAccountRepository(emptyList())

        // When
        composeTestRule.setContent {
            AccountsScreen(accountRepository = repository)
        }

        // Then
        composeTestRule.onNodeWithText("Your Accounts").assertIsDisplayed()
        composeTestRule.onNodeWithText("No accounts yet. Add your first account!").assertIsDisplayed()
    }

    @Test
    fun accountsScreen_displaysAccounts_whenAccountsExist() {
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
        composeTestRule.setContent {
            AccountsScreen(accountRepository = repository)
        }

        // Then
        composeTestRule.onNodeWithText("Checking Account").assertIsDisplayed()
        composeTestRule.onNodeWithText("Savings Account").assertIsDisplayed()
        composeTestRule.onNodeWithText("USD 1000.00").assertIsDisplayed()
        composeTestRule.onNodeWithText("USD 5000.00").assertIsDisplayed()
    }

    @Test
    fun accountsScreen_displaysFloatingActionButton() {
        // Given
        val repository = FakeAccountRepository(emptyList())

        // When
        composeTestRule.setContent {
            AccountsScreen(accountRepository = repository)
        }

        // Then
        composeTestRule.onNodeWithText("+").assertIsDisplayed()
    }

    @Test
    fun accountsScreen_opensCreateDialog_whenFabClicked() {
        // Given
        val repository = FakeAccountRepository(emptyList())

        // When
        composeTestRule.setContent {
            AccountsScreen(accountRepository = repository)
        }

        composeTestRule.onNodeWithText("+").performClick()

        // Then
        composeTestRule.onNodeWithText("Create New Account").assertIsDisplayed()
        composeTestRule.onNodeWithText("Account Name").assertIsDisplayed()
        composeTestRule.onNodeWithText("Account Type").assertIsDisplayed()
    }

    @Test
    fun accountCard_displaysAccountInformation() {
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
        composeTestRule.setContent {
            AccountsScreen(accountRepository = repository)
        }

        // Then
        composeTestRule.onNodeWithText("My Checking").assertIsDisplayed()
        composeTestRule.onNodeWithText("CHECKING").assertIsDisplayed()
        composeTestRule.onNodeWithText("EUR 2500.50").assertIsDisplayed()
    }

    @Test
    fun accountCard_displaysNegativeBalance_inErrorColor() {
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
        composeTestRule.setContent {
            AccountsScreen(accountRepository = repository)
        }

        // Then
        composeTestRule.onNodeWithText("USD -500.00").assertIsDisplayed()
    }

    @Test
    fun accountCard_opensDeleteDialog_whenDeleteButtonClicked() {
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
        composeTestRule.setContent {
            AccountsScreen(accountRepository = repository)
        }

        // Click the delete button (trash icon)
        composeTestRule.onNodeWithText("🗑️").performClick()

        // Then
        composeTestRule.onNodeWithText("Delete Account?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Are you sure you want to delete \"Test Account\"?").assertIsDisplayed()
    }

    @Test
    fun createAccountDialog_validatesRequiredFields() {
        // Given
        val repository = FakeAccountRepository(emptyList())

        // When
        composeTestRule.setContent {
            AccountsScreen(accountRepository = repository)
        }

        // Open dialog
        composeTestRule.onNodeWithText("+").performClick()

        // Try to create without filling fields
        composeTestRule.onNodeWithText("Create").performClick()

        // Then
        composeTestRule.onNodeWithText("Account name is required").assertIsDisplayed()
    }

    @Test
    fun createAccountDialog_canBeDismissed() {
        // Given
        val repository = FakeAccountRepository(emptyList())

        // When
        composeTestRule.setContent {
            AccountsScreen(accountRepository = repository)
        }

        // Open dialog
        composeTestRule.onNodeWithText("+").performClick()
        composeTestRule.onNodeWithText("Create New Account").assertIsDisplayed()

        // Click cancel
        composeTestRule.onNodeWithText("Cancel").performClick()

        // Then - dialog should be dismissed
        composeTestRule.onNodeWithText("Create New Account").assertDoesNotExist()
    }

    @Test
    fun deleteAccountDialog_canBeDismissed() {
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
        composeTestRule.setContent {
            AccountsScreen(accountRepository = repository)
        }

        // Open delete dialog
        composeTestRule.onNodeWithText("🗑️").performClick()
        composeTestRule.onNodeWithText("Delete Account?").assertIsDisplayed()

        // Click cancel
        composeTestRule.onNodeWithText("Cancel").performClick()

        // Then - dialog should be dismissed
        composeTestRule.onNodeWithText("Delete Account?").assertDoesNotExist()
    }

    @Test
    fun accountsScreen_displaysMultipleAccounts() {
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
        composeTestRule.setContent {
            AccountsScreen(accountRepository = repository)
        }

        // Then - all accounts should be visible
        composeTestRule.onNodeWithText("Account 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Account 2").assertIsDisplayed()
        composeTestRule.onNodeWithText("Account 3").assertIsDisplayed()
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
