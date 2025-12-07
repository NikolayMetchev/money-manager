@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.repository

import com.moneymanager.database.DatabaseMaintenanceService
import com.moneymanager.database.RepositorySet
import com.moneymanager.database.createTestDatabaseLocation
import com.moneymanager.database.deleteTestDatabase
import com.moneymanager.di.AppComponent
import com.moneymanager.di.createTestAppComponentParams
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.domain.repository.TransactionRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

class TransactionRepositoryImplTest {
    private lateinit var transactionRepository: TransactionRepository
    private lateinit var accountRepository: AccountRepository
    private lateinit var currencyRepository: CurrencyRepository
    private lateinit var maintenanceService: DatabaseMaintenanceService
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
            val repositories = RepositorySet(database)

            transactionRepository = repositories.transactionRepository
            accountRepository = repositories.accountRepository
            currencyRepository = repositories.currencyRepository
            maintenanceService = repositories.maintenanceService
        }

    @AfterTest
    fun cleanup() {
        deleteTestDatabase(testDbLocation)
    }

    // CREATE TRANSACTION TESTS

    @Test
    fun `createTransfer should insert transaction and transfer`() =
        runTest {
            val now = Clock.System.now()

            // Create test accounts
            val sourceAccountId =
                accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Source Account", openingDate = now),
                )
            val targetAccountId =
                accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Target Account", openingDate = now),
                )

            // Create test currency
            val currencyId = currencyRepository.upsertCurrencyByCode("USD", "US Dollar")

            // Create transfer
            val transferId = TransferId(Uuid.random())
            val transfer =
                Transfer(
                    id = transferId,
                    timestamp = now,
                    description = "Test transaction",
                    sourceAccountId = sourceAccountId,
                    targetAccountId = targetAccountId,
                    currencyId = currencyId,
                    amount = 100.0,
                )
            transactionRepository.createTransfer(transfer)

            val retrieved = transactionRepository.getTransactionById(transferId.id).first()
            assertNotNull(retrieved, "Retrieved transaction should not be null for ID: $transferId")
            assertEquals(sourceAccountId, retrieved.sourceAccountId)
            assertEquals(targetAccountId, retrieved.targetAccountId)
            assertEquals(currencyId, retrieved.currencyId)
            assertEquals(100.0, retrieved.amount)
        }

    @Test
    fun `createTransfer should fail when source and target accounts are the same`() =
        runTest {
            val now = Clock.System.now()

            // Create test account
            val accountId =
                accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Test Account", openingDate = now),
                )

            // Create test currency
            val currencyId = currencyRepository.upsertCurrencyByCode("USD", "US Dollar")

            // Should throw exception due to CHECK constraint
            // Same as source - violates CHECK constraint
            assertFailsWith<Exception> {
                val transfer =
                    Transfer(
                        id = TransferId(Uuid.random()),
                        timestamp = now,
                        description = "Invalid transaction",
                        sourceAccountId = accountId,
                        targetAccountId = accountId,
                        currencyId = currencyId,
                        amount = 100.0,
                    )
                transactionRepository.createTransfer(transfer)
            }
        }

    @Test
    fun `updateTransfer should fail when changing to same source and target accounts`() =
        runTest {
            val now = Clock.System.now()

            // Create test accounts
            val sourceAccountId =
                accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Source Account", openingDate = now),
                )
            val targetAccountId =
                accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Target Account", openingDate = now),
                )

            // Create test currency
            val currencyId = currencyRepository.upsertCurrencyByCode("USD", "US Dollar")

            // Create valid transfer
            val transferId = TransferId(Uuid.random())
            val transfer =
                Transfer(
                    id = transferId,
                    timestamp = now,
                    description = "Test transaction",
                    sourceAccountId = sourceAccountId,
                    targetAccountId = targetAccountId,
                    currencyId = currencyId,
                    amount = 100.0,
                )
            transactionRepository.createTransfer(transfer)

            // Verify transaction was created
            val created = transactionRepository.getTransactionById(transferId.id).first()
            assertNotNull(created, "Transaction should be created")

            // Should throw exception due to CHECK constraint
            // Same as source - violates CHECK constraint
            assertFailsWith<Exception> {
                val invalidTransfer =
                    Transfer(
                        id = transferId,
                        timestamp = now,
                        description = "Test transaction",
                        sourceAccountId = sourceAccountId,
                        targetAccountId = sourceAccountId,
                        currencyId = currencyId,
                        amount = 100.0,
                    )
                transactionRepository.updateTransfer(invalidTransfer)
            }
        }

    // BALANCE CALCULATION TESTS

    @Test
    fun `getAccountBalances should return correct balances after single transaction`() =
        runTest {
            val now = Clock.System.now()

            // Create test accounts
            val checkingAccountId =
                accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Checking", openingDate = now),
                )
            val savingsAccountId =
                accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Savings", openingDate = now),
                )

            // Create test currency
            val usdId = currencyRepository.upsertCurrencyByCode("USD", "US Dollar")

            // Create transaction: Checking -> Savings, 100 USD
            val transfer =
                Transfer(
                    id = TransferId(Uuid.random()),
                    timestamp = now,
                    description = "Transfer to savings",
                    sourceAccountId = checkingAccountId,
                    targetAccountId = savingsAccountId,
                    currencyId = usdId,
                    amount = 100.0,
                )
            transactionRepository.createTransfer(transfer)

            // Refresh materialized views
            maintenanceService.refreshMaterializedViews()

            // Get balances
            val balances = transactionRepository.getAccountBalances().first()

            // Find balances for each account
            val checkingBalance = balances.find { it.accountId == checkingAccountId && it.currencyId == usdId }
            val savingsBalance = balances.find { it.accountId == savingsAccountId && it.currencyId == usdId }

            // Verify balances
            assertNotNull(checkingBalance, "Checking account should have a balance")
            assertNotNull(savingsBalance, "Savings account should have a balance")
            assertEquals(-100.0, checkingBalance.balance, "Checking should have -100 (outgoing)")
            assertEquals(100.0, savingsBalance.balance, "Savings should have +100 (incoming)")
        }

    @Test
    fun `getAccountBalances should return correct balances after multiple transactions`() =
        runTest {
            val now = Clock.System.now()

            // Create test accounts
            val checkingAccountId =
                accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Checking", openingDate = now),
                )
            val savingsAccountId =
                accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Savings", openingDate = now),
                )
            val creditCardAccountId =
                accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Credit Card", openingDate = now),
                )

            // Create test currency
            val usdId = currencyRepository.upsertCurrencyByCode("USD", "US Dollar")

            // Create multiple transactions
            // 1. Checking -> Savings: 100
            transactionRepository.createTransfer(
                Transfer(
                    id = TransferId(Uuid.random()),
                    timestamp = now,
                    description = "Transfer to savings",
                    sourceAccountId = checkingAccountId,
                    targetAccountId = savingsAccountId,
                    currencyId = usdId,
                    amount = 100.0,
                ),
            )

            // 2. Checking -> Credit Card: 50 (payment)
            transactionRepository.createTransfer(
                Transfer(
                    id = TransferId(Uuid.random()),
                    timestamp = now,
                    description = "Credit card payment",
                    sourceAccountId = checkingAccountId,
                    targetAccountId = creditCardAccountId,
                    currencyId = usdId,
                    amount = 50.0,
                ),
            )

            // 3. Savings -> Checking: 30
            transactionRepository.createTransfer(
                Transfer(
                    id = TransferId(Uuid.random()),
                    timestamp = now,
                    description = "Transfer from savings",
                    sourceAccountId = savingsAccountId,
                    targetAccountId = checkingAccountId,
                    currencyId = usdId,
                    amount = 30.0,
                ),
            )

            // Refresh materialized views
            maintenanceService.refreshMaterializedViews()

            // Get balances
            val balances = transactionRepository.getAccountBalances().first()

            // Find balances for each account
            val checkingBalance = balances.find { it.accountId == checkingAccountId && it.currencyId == usdId }
            val savingsBalance = balances.find { it.accountId == savingsAccountId && it.currencyId == usdId }
            val creditCardBalance = balances.find { it.accountId == creditCardAccountId && it.currencyId == usdId }

            // Verify balances
            assertNotNull(checkingBalance)
            assertNotNull(savingsBalance)
            assertNotNull(creditCardBalance)

            // Checking: -100 (out) - 50 (out) + 30 (in) = -120
            assertEquals(-120.0, checkingBalance.balance, "Checking balance should be -120")

            // Savings: +100 (in) - 30 (out) = +70
            assertEquals(70.0, savingsBalance.balance, "Savings balance should be +70")

            // Credit Card: +50 (in) = +50
            assertEquals(50.0, creditCardBalance.balance, "Credit Card balance should be +50")
        }

    @Test
    fun `getAccountBalances should handle multiple currencies per account`() =
        runTest {
            val now = Clock.System.now()

            // Create test accounts
            val checkingAccountId =
                accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Checking", openingDate = now),
                )
            val savingsAccountId =
                accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Savings", openingDate = now),
                )

            // Create test currencies
            val usdId = currencyRepository.upsertCurrencyByCode("USD", "US Dollar")
            val eurId = currencyRepository.upsertCurrencyByCode("EUR", "Euro")

            // Create transactions in different currencies
            // USD: Checking -> Savings, 100
            transactionRepository.createTransfer(
                Transfer(
                    id = TransferId(Uuid.random()),
                    timestamp = now,
                    description = "Transfer USD to savings",
                    sourceAccountId = checkingAccountId,
                    targetAccountId = savingsAccountId,
                    currencyId = usdId,
                    amount = 100.0,
                ),
            )

            // EUR: Checking -> Savings, 50
            transactionRepository.createTransfer(
                Transfer(
                    id = TransferId(Uuid.random()),
                    timestamp = now,
                    description = "Transfer EUR to savings",
                    sourceAccountId = checkingAccountId,
                    targetAccountId = savingsAccountId,
                    currencyId = eurId,
                    amount = 50.0,
                ),
            )

            // Refresh materialized views
            maintenanceService.refreshMaterializedViews()

            // Get balances
            val balances = transactionRepository.getAccountBalances().first()

            // Find balances for checking account
            val checkingUsdBalance = balances.find { it.accountId == checkingAccountId && it.currencyId == usdId }
            val checkingEurBalance = balances.find { it.accountId == checkingAccountId && it.currencyId == eurId }

            // Find balances for savings account
            val savingsUsdBalance = balances.find { it.accountId == savingsAccountId && it.currencyId == usdId }
            val savingsEurBalance = balances.find { it.accountId == savingsAccountId && it.currencyId == eurId }

            // Verify balances
            assertNotNull(checkingUsdBalance)
            assertNotNull(checkingEurBalance)
            assertNotNull(savingsUsdBalance)
            assertNotNull(savingsEurBalance)

            assertEquals(-100.0, checkingUsdBalance.balance, "Checking USD balance should be -100")
            assertEquals(-50.0, checkingEurBalance.balance, "Checking EUR balance should be -50")
            assertEquals(100.0, savingsUsdBalance.balance, "Savings USD balance should be +100")
            assertEquals(50.0, savingsEurBalance.balance, "Savings EUR balance should be +50")
        }

    @Test
    fun `getAccountBalances should return empty list when no transactions exist`() =
        runTest {
            // Create accounts but no transactions
            val now = Clock.System.now()
            accountRepository.createAccount(Account(id = AccountId(0), name = "Checking", openingDate = now))
            accountRepository.createAccount(Account(id = AccountId(0), name = "Savings", openingDate = now))

            val balances = transactionRepository.getAccountBalances().first()

            assertEquals(0, balances.size, "Should have no balances when no transactions exist")
        }

    // OTHER TRANSACTION TESTS

    @Test
    fun `deleteTransaction should remove transaction`() =
        runTest {
            val now = Clock.System.now()

            // Create test accounts
            val sourceAccountId =
                accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Source Account", openingDate = now),
                )
            val targetAccountId =
                accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Target Account", openingDate = now),
                )

            // Create test currency
            val currencyId = currencyRepository.upsertCurrencyByCode("USD", "US Dollar")

            // Create transaction
            val transferId = TransferId(Uuid.random())
            val transfer =
                Transfer(
                    id = transferId,
                    timestamp = now,
                    description = "Test transaction to delete",
                    sourceAccountId = sourceAccountId,
                    targetAccountId = targetAccountId,
                    currencyId = currencyId,
                    amount = 100.0,
                )
            transactionRepository.createTransfer(transfer)

            // Delete transaction
            transactionRepository.deleteTransaction(transferId.id)

            val deleted = transactionRepository.getTransactionById(transferId.id).first()
            assertEquals(null, deleted)
        }

    @Test
    fun `getAllTransactions should return all transactions ordered by timestamp descending`() =
        runTest {
            val now = Clock.System.now()

            // Create test accounts
            val sourceAccountId =
                accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Source Account", openingDate = now),
                )
            val targetAccountId =
                accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Target Account", openingDate = now),
                )

            // Create test currency
            val currencyId = currencyRepository.upsertCurrencyByCode("USD", "US Dollar")

            // Create multiple transactions with different timestamps
            transactionRepository.createTransfer(
                Transfer(
                    id = TransferId(Uuid.random()),
                    timestamp = now - kotlin.time.Duration.parse("1h"),
                    description = "Earlier transaction",
                    sourceAccountId = sourceAccountId,
                    targetAccountId = targetAccountId,
                    currencyId = currencyId,
                    amount = 100.0,
                ),
            )

            transactionRepository.createTransfer(
                Transfer(
                    id = TransferId(Uuid.random()),
                    timestamp = now,
                    description = "Later transaction",
                    sourceAccountId = sourceAccountId,
                    targetAccountId = targetAccountId,
                    currencyId = currencyId,
                    amount = 200.0,
                ),
            )

            val allTransactions = transactionRepository.getAllTransactions().first()
            assertEquals(2, allTransactions.size)

            // Verify descending order by timestamp
            assertTrue(allTransactions[0].timestamp >= allTransactions[1].timestamp)
        }
}
