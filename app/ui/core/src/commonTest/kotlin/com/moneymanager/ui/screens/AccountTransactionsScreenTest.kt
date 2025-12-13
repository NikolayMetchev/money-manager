@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.runComposeUiTest
import com.moneymanager.database.DatabaseMaintenanceService
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountBalance
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AccountRow
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.CategoryBalance
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.domain.repository.TransactionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class AccountTransactionsScreenTest {
    @Test
    fun accountTransactionCard_flipsAccountDisplay_whenPerspectiveChanges() =
        runComposeUiTest {
            // Given: Two accounts and a transfer between them
            val now = Clock.System.now()
            val usdCurrency =
                Currency(
                    id = CurrencyId(Uuid.random()),
                    code = "USD",
                    name = "US Dollar",
                    scaleFactor = 100,
                )

            val checking =
                Account(
                    id = AccountId(1L),
                    name = "Checking",
                    openingDate = now,
                )
            val savings =
                Account(
                    id = AccountId(2L),
                    name = "Savings",
                    openingDate = now,
                )

            val transfer =
                Transfer(
                    id = TransferId(Uuid.random()),
                    timestamp = now,
                    description = "Transfer to savings",
                    sourceAccountId = checking.id,
                    targetAccountId = savings.id,
                    amount = Money.fromDisplayValue(100.0, usdCurrency),
                )

            val accountRepository = FakeAccountRepository(listOf(checking, savings))
            val transactionRepository = FakeTransactionRepository(listOf(transfer))
            val currencyRepository = FakeCurrencyRepository(listOf(usdCurrency))
            val categoryRepository = FakeCategoryRepository()
            val maintenanceService = FakeDatabaseMaintenanceService()

            // When: Viewing from Checking account's perspective
            setContent {
                var currentAccountId by remember { mutableStateOf(checking.id) }

                AccountTransactionsScreen(
                    accountId = currentAccountId,
                    transactionRepository = transactionRepository,
                    accountRepository = accountRepository,
                    categoryRepository = categoryRepository,
                    currencyRepository = currencyRepository,
                    maintenanceService = maintenanceService,
                    onAccountIdChange = { currentAccountId = it },
                    onCurrencyIdChange = {},
                )
            }

            // Then: When viewing Checking's transactions, should show:
            // - 1 "Checking" in matrix header
            // - 1 "Savings" in matrix header
            // - 1 "Savings" in transaction row (the OTHER account from Checking's perspective)
            waitForIdle()

            onAllNodesWithText("Checking").assertCountEquals(1) // Matrix header only
            onAllNodesWithText("Savings").assertCountEquals(2) // Matrix header + transaction row

            // When: Click on "Savings" in the transaction row to switch to Savings perspective
            // Use index [1] (the second node) since [0] is in the matrix
            onAllNodesWithText("Savings")[1].performClick()

            // Wait for state to update and recomposition to occur
            waitForIdle()
            // Wait a bit more to ensure all state updates have propagated
            mainClock.advanceTimeBy(100)

            // Then: After flipping to Savings perspective, should show:
            // - 1 "Checking" in matrix header
            // - 1 "Savings" in matrix header
            // - 1 "Checking" in transaction row (the OTHER account from Savings' perspective)
            // THIS IS THE BUG: It's probably still showing "Savings" instead of "Checking"
            onAllNodesWithText("Savings").assertCountEquals(1) // Matrix header only (NOT in transaction row anymore!)
            onAllNodesWithText("Checking").assertCountEquals(2) // Matrix header + transaction row
        }

    @Test
    fun clickingAccountInTransaction_switchesPerspective() =
        runComposeUiTest {
            // Given: Two accounts with a transfer between them
            val now = Clock.System.now()
            val usdCurrency =
                Currency(
                    id = CurrencyId(Uuid.random()),
                    code = "USD",
                    name = "US Dollar",
                    scaleFactor = 100,
                )

            val checking =
                Account(
                    id = AccountId(1L),
                    name = "Checking",
                    openingDate = now,
                )
            val savings =
                Account(
                    id = AccountId(2L),
                    name = "Savings",
                    openingDate = now,
                )

            val transfer =
                Transfer(
                    id = TransferId(Uuid.random()),
                    timestamp = now,
                    description = "Transfer to savings",
                    sourceAccountId = checking.id,
                    targetAccountId = savings.id,
                    amount = Money.fromDisplayValue(100.0, usdCurrency),
                )

            val accountRepository = FakeAccountRepository(listOf(checking, savings))
            val transactionRepository = FakeTransactionRepository(listOf(transfer))
            val currencyRepository = FakeCurrencyRepository(listOf(usdCurrency))
            val categoryRepository = FakeCategoryRepository()
            val maintenanceService = FakeDatabaseMaintenanceService()

            setContent {
                var currentAccountId by remember { mutableStateOf(checking.id) }

                AccountTransactionsScreen(
                    accountId = currentAccountId,
                    transactionRepository = transactionRepository,
                    accountRepository = accountRepository,
                    categoryRepository = categoryRepository,
                    currencyRepository = currencyRepository,
                    maintenanceService = maintenanceService,
                    onAccountIdChange = { currentAccountId = it },
                    onCurrencyIdChange = {},
                )
            }

            waitForIdle()

            // BEFORE: Viewing Checking account's transactions
            // The transaction row should show "Savings" (the other account)
            println("=== BEFORE CLICK ===")
            onRoot().printToLog("UI_TREE")

            // Find all "Savings" text nodes - should be:
            // [0] = Matrix header
            // [1] = Transaction row (the "other" account from Checking's view)
            val savingsNodesBeforeClick = onAllNodesWithText("Savings")
            savingsNodesBeforeClick.assertCountEquals(2)

            // Click the second "Savings" node (the one in the transaction row)
            println("=== CLICKING SAVINGS IN TRANSACTION ROW ===")
            savingsNodesBeforeClick[1].performClick()

            waitForIdle()
            mainClock.advanceTimeBy(200)

            // AFTER: Now viewing Savings account's transactions
            // The transaction row should now show "Checking" (the other account)
            println("=== AFTER CLICK ===")
            onRoot().printToLog("UI_TREE")

            // Verify "Savings" only appears once (in matrix header, NOT in transaction row)
            onAllNodesWithText("Savings").assertCountEquals(1)

            // Verify "Checking" appears twice:
            // [0] = Matrix header
            // [1] = Transaction row (the "other" account from Savings' view)
            onAllNodesWithText("Checking").assertCountEquals(2)

            println("=== TEST PASSED ===")
        }

    private class FakeAccountRepository(
        private val accounts: List<Account>,
    ) : AccountRepository {
        private val accountsFlow = MutableStateFlow(accounts)

        override fun getAllAccounts(): Flow<List<Account>> = accountsFlow

        override fun getAccountById(id: AccountId): Flow<Account?> = flowOf(accounts.find { it.id == id })

        override suspend fun createAccount(account: Account): AccountId {
            val newId = AccountId((accounts.maxOfOrNull { it.id.id } ?: 0L) + 1)
            val newAccount = account.copy(id = newId)
            accountsFlow.value = accountsFlow.value + newAccount
            return newId
        }

        override suspend fun createAccountsBatch(accounts: List<Account>): List<AccountId> {
            return accounts.map { createAccount(it) }
        }

        override suspend fun updateAccount(account: Account) {
            accountsFlow.value = accountsFlow.value.map { if (it.id == account.id) account else it }
        }

        override suspend fun deleteAccount(id: AccountId) {
            accountsFlow.value = accountsFlow.value.filter { it.id != id }
        }
    }

    private class FakeTransactionRepository(
        private val transfers: List<Transfer>,
    ) : TransactionRepository {
        override fun getTransactionById(id: Uuid): Flow<Transfer?> = flowOf(transfers.find { it.id.id == id })

        override fun getTransactionsByAccount(accountId: AccountId): Flow<List<Transfer>> =
            flowOf(transfers.filter { it.sourceAccountId == accountId || it.targetAccountId == accountId })

        override fun getTransactionsByDateRange(
            startDate: Instant,
            endDate: Instant,
        ): Flow<List<Transfer>> = flowOf(emptyList())

        override fun getTransactionsByAccountAndDateRange(
            accountId: AccountId,
            startDate: Instant,
            endDate: Instant,
        ): Flow<List<Transfer>> = flowOf(emptyList())

        override fun getAccountBalances(): Flow<List<AccountBalance>> = flowOf(emptyList())

        override suspend fun getRunningBalanceByAccountPaginated(
            accountId: AccountId,
            pageSize: Int,
            pagingInfo: com.moneymanager.domain.model.PagingInfo?,
        ): com.moneymanager.domain.model.PagingResult<AccountRow> {
            // Simulate the materialized view logic: create TWO AccountRow entries per transfer
            // One from the source account's perspective (outgoing = negative)
            // One from the target account's perspective (incoming = positive)
            val allRows =
                transfers.flatMap { transfer ->
                    listOf(
                        // Source account's perspective (outgoing = negative)
                        AccountRow(
                            transactionId = transfer.id,
                            timestamp = transfer.timestamp,
                            description = transfer.description,
                            accountId = transfer.sourceAccountId,
                            transactionAmount = Money(-transfer.amount.amount, transfer.amount.currency),
                            runningBalance = transfer.amount,
                            sourceAccountId = transfer.sourceAccountId,
                            targetAccountId = transfer.targetAccountId,
                        ),
                        // Target account's perspective (incoming = positive)
                        AccountRow(
                            transactionId = transfer.id,
                            timestamp = transfer.timestamp,
                            description = transfer.description,
                            accountId = transfer.targetAccountId,
                            transactionAmount = transfer.amount,
                            runningBalance = transfer.amount,
                            sourceAccountId = transfer.sourceAccountId,
                            targetAccountId = transfer.targetAccountId,
                        ),
                    )
                }.filter { it.accountId == accountId }
                    .sortedByDescending { it.timestamp }

            // Simple pagination for testing
            val items = allRows.take(pageSize)
            val hasMore = allRows.size > pageSize

            return com.moneymanager.domain.model.PagingResult(
                items = items,
                pagingInfo =
                    com.moneymanager.domain.model.PagingInfo(
                        lastTimestamp = items.lastOrNull()?.timestamp,
                        lastId = items.lastOrNull()?.transactionId,
                        hasMore = hasMore,
                    ),
            )
        }

        override suspend fun createTransfer(transfer: Transfer) {}

        override suspend fun createTransfersBatch(transfers: List<Transfer>) {}

        override suspend fun updateTransfer(transfer: Transfer) {}

        override suspend fun deleteTransaction(id: Uuid) {}
    }

    private class FakeCurrencyRepository(
        private val currencies: List<Currency>,
    ) : CurrencyRepository {
        override fun getAllCurrencies(): Flow<List<Currency>> = flowOf(currencies)

        override fun getCurrencyById(id: CurrencyId): Flow<Currency?> = flowOf(currencies.find { it.id == id })

        override fun getCurrencyByCode(code: String): Flow<Currency?> = flowOf(currencies.find { it.code == code })

        override suspend fun upsertCurrencyByCode(
            code: String,
            name: String,
        ): CurrencyId = CurrencyId(Uuid.random())

        override suspend fun updateCurrency(currency: Currency) {}

        override suspend fun deleteCurrency(id: CurrencyId) {}
    }

    private class FakeCategoryRepository : CategoryRepository {
        private val categories =
            listOf(
                Category(id = -1L, name = "Uncategorized", parentId = null),
                Category(id = 1L, name = "Food", parentId = null),
                Category(id = 2L, name = "Transport", parentId = null),
            )

        override fun getAllCategories(): Flow<List<Category>> = flowOf(categories)

        override fun getCategoryBalances(): Flow<List<CategoryBalance>> = flowOf(emptyList())

        override fun getCategoryById(id: Long): Flow<Category?> = flowOf(categories.find { it.id == id })

        override fun getTopLevelCategories(): Flow<List<Category>> = flowOf(categories.filter { it.parentId == null })

        override fun getCategoriesByParent(parentId: Long): Flow<List<Category>> = flowOf(categories.filter { it.parentId == parentId })

        override suspend fun createCategory(category: Category): Long = 0L

        override suspend fun updateCategory(category: Category) {}

        override suspend fun deleteCategory(id: Long) {}
    }

    private class FakeDatabaseMaintenanceService : DatabaseMaintenanceService {
        override suspend fun reindex(): Duration = Duration.ZERO

        override suspend fun vacuum(): Duration = Duration.ZERO

        override suspend fun analyze(): Duration = Duration.ZERO

        override suspend fun refreshMaterializedViews(): Duration = Duration.ZERO

        override suspend fun fullRefreshMaterializedViews(): Duration = Duration.ZERO
    }
}
