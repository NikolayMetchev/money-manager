@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.ui.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.PageWithTargetIndex
import com.moneymanager.domain.model.PagingInfo
import com.moneymanager.domain.model.PagingResult
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.repository.AccountAttributeWriteRepository
import com.moneymanager.domain.repository.AccountWriteRepository
import com.moneymanager.domain.repository.AttributeTypeWriteRepository
import com.moneymanager.domain.repository.CategoryWriteRepository
import com.moneymanager.domain.repository.PersonAccountOwnershipWriteRepository
import com.moneymanager.domain.repository.PersonWriteRepository
import com.moneymanager.domain.repository.TransactionWriteRepository
import com.moneymanager.ui.error.ProvideSchemaAwareScope
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Duration

@OptIn(ExperimentalTestApi::class)
class AccountsScreenTest {
    @Test
    fun accountsScreen_displaysEmptyState_whenNoAccounts() =
        runMoneyManagerComposeUiTest {
            // Given
            val repository = createAccountRepository(emptyList())

            // When
            setContent {
                ProvideSchemaAwareScope {
                    AccountsScreen(
                        accountRepository = repository,
                        accountAttributeRepository = createAccountAttributeRepository(),
                        attributeTypeRepository = createAttributeTypeRepository(),
                        categoryRepository = createCategoryRepository(),
                        transactionRepository = createTransactionRepository(),
                        personRepository = createPersonRepository(),
                        personAccountOwnershipRepository = createPersonAccountOwnershipRepository(),
                        maintenance = createMaintenance(),
                        scrollToAccountId = null,
                        onAccountClick = {},
                    )
                }
            }

            // Then
            onNodeWithText("Your Accounts").assertIsDisplayed()
            onNodeWithText("No accounts yet. Add your first account!").assertIsDisplayed()
        }

    @Test
    fun accountsScreen_displaysAccounts_whenAccountsExist() =
        runMoneyManagerComposeUiTest {
            // Given
            val now = Clock.System.now()
            val accounts =
                listOf(
                    Account(
                        id = AccountId(1L),
                        name = "Checking Account",
                        openingDate = now,
                    ),
                    Account(
                        id = AccountId(2L),
                        name = "Savings Account",
                        openingDate = now,
                    ),
                )
            val repository = createAccountRepository(accounts)

            // When
            setContent {
                ProvideSchemaAwareScope {
                    AccountsScreen(
                        accountRepository = repository,
                        accountAttributeRepository = createAccountAttributeRepository(),
                        attributeTypeRepository = createAttributeTypeRepository(),
                        categoryRepository = createCategoryRepository(),
                        transactionRepository = createTransactionRepository(),
                        personRepository = createPersonRepository(),
                        personAccountOwnershipRepository = createPersonAccountOwnershipRepository(),
                        maintenance = createMaintenance(),
                        scrollToAccountId = null,
                        onAccountClick = {},
                    )
                }
            }

            // Then
            onNodeWithText("Checking Account").assertIsDisplayed()
            onNodeWithText("Savings Account").assertIsDisplayed()
        }

    @Test
    fun accountsScreen_displaysAddAccountButton() =
        runMoneyManagerComposeUiTest {
            // Given
            val repository = createAccountRepository(emptyList())

            // When
            setContent {
                ProvideSchemaAwareScope {
                    AccountsScreen(
                        accountRepository = repository,
                        accountAttributeRepository = createAccountAttributeRepository(),
                        attributeTypeRepository = createAttributeTypeRepository(),
                        categoryRepository = createCategoryRepository(),
                        transactionRepository = createTransactionRepository(),
                        personRepository = createPersonRepository(),
                        personAccountOwnershipRepository = createPersonAccountOwnershipRepository(),
                        maintenance = createMaintenance(),
                        scrollToAccountId = null,
                        onAccountClick = {},
                    )
                }
            }

            // Then
            onNodeWithText("+ Add Account").assertIsDisplayed()
        }

    @Test
    fun accountsScreen_opensCreateDialog_whenAddAccountClicked() =
        runMoneyManagerComposeUiTest {
            // Given
            val repository = createAccountRepository(emptyList())

            // When
            setContent {
                ProvideSchemaAwareScope {
                    AccountsScreen(
                        accountRepository = repository,
                        accountAttributeRepository = createAccountAttributeRepository(),
                        attributeTypeRepository = createAttributeTypeRepository(),
                        categoryRepository = createCategoryRepository(),
                        transactionRepository = createTransactionRepository(),
                        personRepository = createPersonRepository(),
                        personAccountOwnershipRepository = createPersonAccountOwnershipRepository(),
                        maintenance = createMaintenance(),
                        scrollToAccountId = null,
                        onAccountClick = {},
                    )
                }
            }

            onNodeWithText("+ Add Account").performClick()

            // Then
            onNodeWithText("Create New Account").assertIsDisplayed()
            onNodeWithText("Account Name").assertIsDisplayed()
        }

    @Test
    fun createAccountDialog_addsOwnersFromDropdown() =
        runMoneyManagerComposeUiTest {
            // Given
            val repository = createAccountRepository(emptyList())
            val people =
                listOf(
                    Person(id = PersonId(1L), firstName = "Alice", middleName = null, lastName = null),
                    Person(id = PersonId(2L), firstName = "Bob", middleName = null, lastName = null),
                )

            // When
            setContent {
                ProvideSchemaAwareScope {
                    AccountsScreen(
                        accountRepository = repository,
                        accountAttributeRepository = createAccountAttributeRepository(),
                        attributeTypeRepository = createAttributeTypeRepository(),
                        categoryRepository = createCategoryRepository(),
                        transactionRepository = createTransactionRepository(),
                        personRepository = createPersonRepository(people),
                        personAccountOwnershipRepository = createPersonAccountOwnershipRepository(),
                        maintenance = createMaintenance(),
                        scrollToAccountId = null,
                        onAccountClick = {},
                    )
                }
            }

            onNodeWithText("+ Add Account").performClick()
            onNodeWithText("Select owner").performClick()
            onNodeWithText("Alice").performClick()
            onNodeWithText("+ Add Owner").performClick()

            // Then
            onNodeWithText("Remove").assertIsDisplayed()
        }

    @Test
    fun accountCard_displaysAccountInformation() =
        runMoneyManagerComposeUiTest {
            // Given
            val now = Clock.System.now()
            val account =
                Account(
                    id = AccountId(1L),
                    name = "My Checking",
                    openingDate = now,
                )
            val repository = createAccountRepository(listOf(account))

            // When
            setContent {
                ProvideSchemaAwareScope {
                    AccountsScreen(
                        accountRepository = repository,
                        accountAttributeRepository = createAccountAttributeRepository(),
                        attributeTypeRepository = createAttributeTypeRepository(),
                        categoryRepository = createCategoryRepository(),
                        transactionRepository = createTransactionRepository(),
                        personRepository = createPersonRepository(),
                        personAccountOwnershipRepository = createPersonAccountOwnershipRepository(),
                        maintenance = createMaintenance(),
                        scrollToAccountId = null,
                        onAccountClick = {},
                    )
                }
            }

            // Then
            onNodeWithText("My Checking").assertIsDisplayed()
        }

    @Test
    fun accountCard_opensDeleteDialog_whenDeleteButtonClicked() =
        runMoneyManagerComposeUiTest {
            // Given
            val now = Clock.System.now()
            val account =
                Account(
                    id = AccountId(1L),
                    name = "Test Account",
                    openingDate = now,
                )
            val repository = createAccountRepository(listOf(account))

            // When
            setContent {
                ProvideSchemaAwareScope {
                    AccountsScreen(
                        accountRepository = repository,
                        accountAttributeRepository = createAccountAttributeRepository(),
                        attributeTypeRepository = createAttributeTypeRepository(),
                        categoryRepository = createCategoryRepository(),
                        transactionRepository = createTransactionRepository(),
                        personRepository = createPersonRepository(),
                        personAccountOwnershipRepository = createPersonAccountOwnershipRepository(),
                        maintenance = createMaintenance(),
                        scrollToAccountId = null,
                        onAccountClick = {},
                    )
                }
            }

            // Click the delete button (trash icon)
            onNodeWithText("🗑️").performClick()

            // Then
            onNodeWithText("Delete Account?").assertIsDisplayed()
            onNodeWithText("Are you sure you want to delete \"Test Account\"?").assertIsDisplayed()
        }

    @Test
    fun createAccountDialog_validatesRequiredFields() =
        runMoneyManagerComposeUiTest {
            // Given
            val repository = createAccountRepository(emptyList())

            // When
            setContent {
                ProvideSchemaAwareScope {
                    AccountsScreen(
                        accountRepository = repository,
                        accountAttributeRepository = createAccountAttributeRepository(),
                        attributeTypeRepository = createAttributeTypeRepository(),
                        categoryRepository = createCategoryRepository(),
                        transactionRepository = createTransactionRepository(),
                        personRepository = createPersonRepository(),
                        personAccountOwnershipRepository = createPersonAccountOwnershipRepository(),
                        maintenance = createMaintenance(),
                        scrollToAccountId = null,
                        onAccountClick = {},
                    )
                }
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
        runMoneyManagerComposeUiTest {
            // Given
            val repository = createAccountRepository(emptyList())

            // When
            setContent {
                ProvideSchemaAwareScope {
                    AccountsScreen(
                        accountRepository = repository,
                        accountAttributeRepository = createAccountAttributeRepository(),
                        attributeTypeRepository = createAttributeTypeRepository(),
                        categoryRepository = createCategoryRepository(),
                        transactionRepository = createTransactionRepository(),
                        personRepository = createPersonRepository(),
                        personAccountOwnershipRepository = createPersonAccountOwnershipRepository(),
                        maintenance = createMaintenance(),
                        scrollToAccountId = null,
                        onAccountClick = {},
                    )
                }
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
        runMoneyManagerComposeUiTest {
            // Given
            val now = Clock.System.now()
            val account =
                Account(
                    id = AccountId(1L),
                    name = "Test Account",
                    openingDate = now,
                )
            val repository = createAccountRepository(listOf(account))

            // When
            setContent {
                ProvideSchemaAwareScope {
                    AccountsScreen(
                        accountRepository = repository,
                        accountAttributeRepository = createAccountAttributeRepository(),
                        attributeTypeRepository = createAttributeTypeRepository(),
                        categoryRepository = createCategoryRepository(),
                        transactionRepository = createTransactionRepository(),
                        personRepository = createPersonRepository(),
                        personAccountOwnershipRepository = createPersonAccountOwnershipRepository(),
                        maintenance = createMaintenance(),
                        scrollToAccountId = null,
                        onAccountClick = {},
                    )
                }
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
        runMoneyManagerComposeUiTest {
            // Given
            val now = Clock.System.now()
            val accounts =
                listOf(
                    Account(
                        id = AccountId(1L),
                        name = "Account 1",
                        openingDate = now,
                    ),
                    Account(
                        id = AccountId(2L),
                        name = "Account 2",
                        openingDate = now,
                    ),
                    Account(
                        id = AccountId(3L),
                        name = "Account 3",
                        openingDate = now,
                    ),
                )
            val repository = createAccountRepository(accounts)

            // When
            setContent {
                ProvideSchemaAwareScope {
                    AccountsScreen(
                        accountRepository = repository,
                        accountAttributeRepository = createAccountAttributeRepository(),
                        attributeTypeRepository = createAttributeTypeRepository(),
                        categoryRepository = createCategoryRepository(),
                        transactionRepository = createTransactionRepository(),
                        personRepository = createPersonRepository(),
                        personAccountOwnershipRepository = createPersonAccountOwnershipRepository(),
                        maintenance = createMaintenance(),
                        scrollToAccountId = null,
                        onAccountClick = {},
                    )
                }
            }

            // Then - all accounts should be visible
            onNodeWithText("Account 1").assertIsDisplayed()
            onNodeWithText("Account 2").assertIsDisplayed()
            onNodeWithText("Account 3").assertIsDisplayed()
        }

    private fun createAccountRepository(accounts: List<Account>): AccountWriteRepository =
        mock(MockMode.autoUnit) {
            every { getAllAccounts() } returns flowOf(accounts)
            every { getAccountById(any()) } returns flowOf(null)
            everySuspend { createAccount(any(), any()) } returns AccountId(999L)
            everySuspend { createAccountsBatch(any(), any()) } returns emptyList()
            everySuspend { updateAccount(any(), any()) } returns 1L
            everySuspend { updateAccountWithAttributes(any(), any(), any(), any(), any(), any()) } returns 1L
            everySuspend { countTransfersByAccount(any()) } returns 0L
            everySuspend { getTransfersBetweenAccounts(any(), any()) } returns emptyList()
        }

    private fun createTransactionRepository(): TransactionWriteRepository =
        mock(MockMode.autoUnit) {
            every { getTransactionById(any()) } returns flowOf(null)
            every { getTransactionsByAccount(any()) } returns flowOf(emptyList())
            every { getTransactionsByDateRange(any(), any()) } returns flowOf(emptyList())
            every { getTransactionsByAccountAndDateRange(any(), any(), any()) } returns flowOf(emptyList())
            every { getAccountBalances() } returns flowOf(emptyList())
            everySuspend { getRunningBalanceByAccountPaginated(any(), any(), any(), any()) } returns
                PagingResult(emptyList(), PagingInfo(null, null, false))
            everySuspend { getRunningBalanceByAccountPaginatedBackward(any(), any(), any(), any(), any()) } returns
                PagingResult(emptyList(), PagingInfo(null, null, false))
            everySuspend { getPageContainingTransaction(any(), any(), any(), any()) } returns
                PageWithTargetIndex(emptyList(), -1, PagingInfo(null, null, false), false)
        }

    private fun createCategoryRepository(): CategoryWriteRepository =
        mock(MockMode.autoUnit) {
            every { getAllCategories() } returns flowOf(emptyList())
            every { getCategoryBalances() } returns flowOf(emptyList())
            every { getCategoryById(any()) } returns flowOf(null)
            every { getTopLevelCategories() } returns flowOf(emptyList())
            every { getCategoriesByParent(any()) } returns flowOf(emptyList())
            everySuspend { createCategory(any(), any()) } returns 0L
        }

    private fun createPersonRepository(people: List<Person> = emptyList()): PersonWriteRepository =
        mock(MockMode.autoUnit) {
            every { getAllPeople() } returns flowOf(people)
            every { getPersonById(any()) } returns flowOf(null)
            everySuspend { createPerson(any(), any()) } returns PersonId(0L)
        }

    private fun createPersonAccountOwnershipRepository(): PersonAccountOwnershipWriteRepository =
        mock(MockMode.autoUnit) {
            every { getOwnershipsByPerson(any()) } returns flowOf(emptyList())
            every { getOwnershipsByAccount(any()) } returns flowOf(emptyList())
            every { getAllOwnerships() } returns flowOf(emptyList())
            every { getOwnershipById(any()) } returns flowOf(null)
            everySuspend { createOwnership(any(), any(), any()) } returns 0L
        }

    private fun createMaintenance(): Maintenance =
        mock(MockMode.autoUnit) {
            everySuspend { reindex() } returns Duration.ZERO
            everySuspend { vacuum() } returns Duration.ZERO
            everySuspend { analyze() } returns Duration.ZERO
            everySuspend { refreshMaterializedViews() } returns Duration.ZERO
            everySuspend { fullRefreshMaterializedViews() } returns Duration.ZERO
        }

    private fun createAccountAttributeRepository(): AccountAttributeWriteRepository =
        mock(MockMode.autoUnit) {
            every { getByAccount(any()) } returns flowOf(emptyList())
            everySuspend { insert(any(), any(), any()) } returns 0L
            everySuspend { insertInCreationMode(any(), any(), any()) } returns 0L
        }

    private fun createAttributeTypeRepository(): AttributeTypeWriteRepository =
        mock(MockMode.autoUnit) {
            every { getAll() } returns flowOf(emptyList())
            every { getById(any()) } returns flowOf(null)
            every { getByName(any()) } returns flowOf(null)
            everySuspend { getOrCreate(any()) } returns AttributeTypeId(0L)
        }
}
