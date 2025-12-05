@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.ui.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountBalance
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.TransactionWithRunningBalance
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.domain.repository.TransactionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class AccountsScreenTest {
    @Test
    fun accountsScreen_displaysEmptyState_whenNoAccounts() =
        runComposeUiTest {
            // Given
            val repository = FakeAccountRepository(emptyList())

            // When
            setContent {
                AccountsScreen(
                    accountRepository = repository,
                    transactionRepository = FakeTransactionRepository(),
                    currencyRepository = FakeCurrencyRepository(),
                    onAccountClick = {},
                )
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
                        openingDate = now,
                    ),
                    Account(
                        id = 2L,
                        name = "Savings Account",
                        openingDate = now,
                    ),
                )
            val repository = FakeAccountRepository(accounts)

            // When
            setContent {
                AccountsScreen(
                    accountRepository = repository,
                    transactionRepository = FakeTransactionRepository(),
                    currencyRepository = FakeCurrencyRepository(),
                    onAccountClick = {},
                )
            }

            // Then
            onNodeWithText("Checking Account").assertIsDisplayed()
            onNodeWithText("Savings Account").assertIsDisplayed()
        }

    @Test
    fun accountsScreen_displaysAddAccountButton() =
        runComposeUiTest {
            // Given
            val repository = FakeAccountRepository(emptyList())

            // When
            setContent {
                AccountsScreen(
                    accountRepository = repository,
                    transactionRepository = FakeTransactionRepository(),
                    currencyRepository = FakeCurrencyRepository(),
                    onAccountClick = {},
                )
            }

            // Then
            onNodeWithText("+ Add Account").assertIsDisplayed()
        }

    @Test
    fun accountsScreen_opensCreateDialog_whenAddAccountClicked() =
        runComposeUiTest {
            // Given
            val repository = FakeAccountRepository(emptyList())

            // When
            setContent {
                AccountsScreen(
                    accountRepository = repository,
                    transactionRepository = FakeTransactionRepository(),
                    currencyRepository = FakeCurrencyRepository(),
                    onAccountClick = {},
                )
            }

            onNodeWithText("+ Add Account").performClick()

            // Then
            onNodeWithText("Create New Account").assertIsDisplayed()
            onNodeWithText("Account Name").assertIsDisplayed()
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
                    openingDate = now,
                )
            val repository = FakeAccountRepository(listOf(account))

            // When
            setContent {
                AccountsScreen(
                    accountRepository = repository,
                    transactionRepository = FakeTransactionRepository(),
                    currencyRepository = FakeCurrencyRepository(),
                    onAccountClick = {},
                )
            }

            // Then
            onNodeWithText("My Checking").assertIsDisplayed()
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
                    openingDate = now,
                )
            val repository = FakeAccountRepository(listOf(account))

            // When
            setContent {
                AccountsScreen(
                    accountRepository = repository,
                    transactionRepository = FakeTransactionRepository(),
                    currencyRepository = FakeCurrencyRepository(),
                    onAccountClick = {},
                )
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
                AccountsScreen(
                    accountRepository = repository,
                    transactionRepository = FakeTransactionRepository(),
                    currencyRepository = FakeCurrencyRepository(),
                    onAccountClick = {},
                )
            }

            // Open dialog
            onNodeWithText("+ Add Account").performClick()

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
                AccountsScreen(
                    accountRepository = repository,
                    transactionRepository = FakeTransactionRepository(),
                    currencyRepository = FakeCurrencyRepository(),
                    onAccountClick = {},
                )
            }

            // Open dialog
            onNodeWithText("+ Add Account").performClick()
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
                    openingDate = now,
                )
            val repository = FakeAccountRepository(listOf(account))

            // When
            setContent {
                AccountsScreen(
                    accountRepository = repository,
                    transactionRepository = FakeTransactionRepository(),
                    currencyRepository = FakeCurrencyRepository(),
                    onAccountClick = {},
                )
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
                        openingDate = now,
                    ),
                    Account(
                        id = 2L,
                        name = "Account 2",
                        openingDate = now,
                    ),
                    Account(
                        id = 3L,
                        name = "Account 3",
                        openingDate = now,
                    ),
                )
            val repository = FakeAccountRepository(accounts)

            // When
            setContent {
                AccountsScreen(
                    accountRepository = repository,
                    transactionRepository = FakeTransactionRepository(),
                    currencyRepository = FakeCurrencyRepository(),
                    onAccountClick = {},
                )
            }

            // Then - all accounts should be visible
            onNodeWithText("Account 1").assertIsDisplayed()
            onNodeWithText("Account 2").assertIsDisplayed()
            onNodeWithText("Account 3").assertIsDisplayed()
        }

    // Fake repositories for testing
    private class FakeAccountRepository(
        private val accounts: List<Account>,
    ) : AccountRepository {
        private val accountsFlow = MutableStateFlow(accounts)
        private val deletedAccounts = mutableListOf<Long>()

        override fun getAllAccounts(): Flow<List<Account>> = accountsFlow

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

    private class FakeTransactionRepository : TransactionRepository {
        override fun getAllTransactions(): Flow<List<Transfer>> = flowOf(emptyList())

        override fun getTransactionById(id: Uuid): Flow<Transfer?> = flowOf(null)

        override fun getTransactionsByAccount(accountId: Long): Flow<List<Transfer>> = flowOf(emptyList())

        override fun getTransactionsByDateRange(
            startDate: Instant,
            endDate: Instant,
        ): Flow<List<Transfer>> = flowOf(emptyList())

        override fun getTransactionsByAccountAndDateRange(
            accountId: Long,
            startDate: Instant,
            endDate: Instant,
        ): Flow<List<Transfer>> = flowOf(emptyList())

        override fun getAccountBalances(): Flow<List<AccountBalance>> = flowOf(emptyList())

        override fun getRunningBalanceByAccount(accountId: Long): Flow<List<TransactionWithRunningBalance>> = flowOf(emptyList())

        override suspend fun createTransfer(transfer: Transfer) {}

        override suspend fun updateTransfer(transfer: Transfer) {}

        override suspend fun deleteTransaction(id: Uuid) {}
    }

    private class FakeCurrencyRepository : CurrencyRepository {
        override fun getAllCurrencies(): Flow<List<Currency>> = flowOf(emptyList())

        override fun getCurrencyById(id: Uuid): Flow<Currency?> = flowOf(null)

        override fun getCurrencyByCode(code: String): Flow<Currency?> = flowOf(null)

        override suspend fun upsertCurrencyByCode(
            code: String,
            name: String,
        ): Uuid = Uuid.random()

        override suspend fun updateCurrency(currency: Currency) {}

        override suspend fun deleteCurrency(id: Uuid) {}
    }
}
