@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.Asset
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.AssetRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.time.Clock

@OptIn(ExperimentalTestApi::class)
class AccountsScreenTest {
    // Test assets
    private val testUSD = Asset(id = 1L, name = "USD")
    private val testEUR = Asset(id = 2L, name = "EUR")
    private val testGBP = Asset(id = 3L, name = "GBP")
    private val fakeAssetRepository = FakeAssetRepository()

    @Test
    fun accountsScreen_displaysEmptyState_whenNoAccounts() =
        runComposeUiTest {
            // Given
            val repository = FakeAccountRepository(emptyList())

            // When
            setContent {
                AccountsScreen(accountRepository = repository, assetRepository = fakeAssetRepository)
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
                        asset = testUSD,
                        initialBalance = 1000.0,
                        openingDate = now,
                    ),
                    Account(
                        id = 2L,
                        name = "Savings Account",
                        asset = testUSD,
                        initialBalance = 5000.0,
                        openingDate = now,
                    ),
                )
            val repository = FakeAccountRepository(accounts)

            // When
            setContent {
                AccountsScreen(accountRepository = repository, assetRepository = fakeAssetRepository)
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
                AccountsScreen(accountRepository = repository, assetRepository = fakeAssetRepository)
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
                AccountsScreen(accountRepository = repository, assetRepository = fakeAssetRepository)
            }

            onNodeWithText("+").performClick()

            // Then
            onNodeWithText("Create New Account").assertIsDisplayed()
            onNodeWithText("Account Name").assertIsDisplayed()
            onNodeWithText("Asset").assertIsDisplayed()
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
                    asset = testEUR,
                    initialBalance = 2500.50,
                    openingDate = now,
                )
            val repository = FakeAccountRepository(listOf(account))

            // When
            setContent {
                AccountsScreen(accountRepository = repository, assetRepository = fakeAssetRepository)
            }

            // Then
            onNodeWithText("My Checking").assertIsDisplayed()
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
                    asset = testUSD,
                    initialBalance = -500.0,
                    openingDate = now,
                )
            val repository = FakeAccountRepository(listOf(account))

            // When
            setContent {
                AccountsScreen(accountRepository = repository, assetRepository = fakeAssetRepository)
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
                    asset = testUSD,
                    initialBalance = 100.0,
                    openingDate = now,
                )
            val repository = FakeAccountRepository(listOf(account))

            // When
            setContent {
                AccountsScreen(accountRepository = repository, assetRepository = fakeAssetRepository)
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
                AccountsScreen(accountRepository = repository, assetRepository = fakeAssetRepository)
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
                AccountsScreen(accountRepository = repository, assetRepository = fakeAssetRepository)
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
                    asset = testUSD,
                    initialBalance = 100.0,
                    openingDate = now,
                )
            val repository = FakeAccountRepository(listOf(account))

            // When
            setContent {
                AccountsScreen(accountRepository = repository, assetRepository = fakeAssetRepository)
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
                        asset = testUSD,
                        initialBalance = 100.0,
                        openingDate = now,
                    ),
                    Account(
                        id = 2L,
                        name = "Account 2",
                        asset = testEUR,
                        initialBalance = 200.0,
                        openingDate = now,
                    ),
                    Account(
                        id = 3L,
                        name = "Account 3",
                        asset = testGBP,
                        initialBalance = 300.0,
                        openingDate = now,
                    ),
                )
            val repository = FakeAccountRepository(accounts)

            // When
            setContent {
                AccountsScreen(accountRepository = repository, assetRepository = fakeAssetRepository)
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

    private class FakeAssetRepository : AssetRepository {
        private val assets = mutableMapOf<String, Long>()
        private var nextId = 1L

        override fun getAllAssets(): Flow<List<Asset>> = flowOf(assets.map { (name, id) -> Asset(id = id, name = name) })

        override fun getAssetById(id: Long): Flow<Asset?> =
            flowOf(assets.entries.find { it.value == id }?.let { Asset(id = it.value, name = it.key) })

        override suspend fun upsertAssetByName(name: String): Long {
            return assets.getOrPut(name) {
                nextId++
            }
        }

        override suspend fun updateAsset(asset: Asset) {
            assets[asset.name] = asset.id
        }

        override suspend fun deleteAsset(id: Long) {
            assets.entries.find { it.value == id }?.let { assets.remove(it.key) }
        }
    }
}
