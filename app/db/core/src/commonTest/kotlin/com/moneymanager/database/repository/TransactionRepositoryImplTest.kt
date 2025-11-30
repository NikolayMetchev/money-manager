@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.repository

import com.moneymanager.database.RepositorySet
import com.moneymanager.database.createTestDatabaseLocation
import com.moneymanager.database.deleteTestDatabase
import com.moneymanager.di.AppComponent
import com.moneymanager.di.createTestAppComponentParams
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.Transaction
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.AssetRepository
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

class TransactionRepositoryImplTest {
    private lateinit var transactionRepository: TransactionRepository
    private lateinit var accountRepository: AccountRepository
    private lateinit var assetRepository: AssetRepository
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
            assetRepository = repositories.assetRepository
        }

    @AfterTest
    fun cleanup() {
        deleteTestDatabase(testDbLocation)
    }

    // CREATE TRANSACTION TESTS

    @Test
    fun `createTransaction should insert transaction and return generated id`() =
        runTest {
            val now = Clock.System.now()

            // Create test accounts
            val sourceAccountId =
                accountRepository.createAccount(
                    Account(name = "Source Account", openingDate = now),
                )
            val targetAccountId =
                accountRepository.createAccount(
                    Account(name = "Target Account", openingDate = now),
                )

            // Create test asset
            val assetId = assetRepository.upsertAssetByName("USD")

            // Create transaction
            val transaction =
                Transaction(
                    sourceAccountId = sourceAccountId,
                    targetAccountId = targetAccountId,
                    assetId = assetId,
                    amount = 100.0,
                    timestamp = now,
                )

            val transactionId = transactionRepository.createTransaction(transaction)

            assertTrue(transactionId > 0, "Generated ID should be positive, but was: $transactionId")

            val retrieved = transactionRepository.getTransactionById(transactionId).first()
            assertNotNull(retrieved, "Retrieved transaction should not be null for ID: $transactionId")
            assertEquals(sourceAccountId, retrieved.sourceAccountId)
            assertEquals(targetAccountId, retrieved.targetAccountId)
            assertEquals(assetId, retrieved.assetId)
            assertEquals(100.0, retrieved.amount)
        }

    @Test
    fun `createTransaction should fail when source and target accounts are the same`() =
        runTest {
            val now = Clock.System.now()

            // Create test account
            val accountId =
                accountRepository.createAccount(
                    Account(name = "Test Account", openingDate = now),
                )

            // Create test asset
            val assetId = assetRepository.upsertAssetByName("USD")

            // Attempt to create transaction with same source and target
            val transaction =
                Transaction(
                    sourceAccountId = accountId,
                    targetAccountId = accountId, // Same as source - violates CHECK constraint
                    assetId = assetId,
                    amount = 100.0,
                    timestamp = now,
                )

            // Should throw exception due to CHECK constraint
            assertFailsWith<Exception> {
                transactionRepository.createTransaction(transaction)
            }
        }

    @Test
    fun `updateTransaction should fail when changing to same source and target accounts`() =
        runTest {
            val now = Clock.System.now()

            // Create test accounts
            val sourceAccountId =
                accountRepository.createAccount(
                    Account(name = "Source Account", openingDate = now),
                )
            val targetAccountId =
                accountRepository.createAccount(
                    Account(name = "Target Account", openingDate = now),
                )

            // Create test asset
            val assetId = assetRepository.upsertAssetByName("USD")

            // Create valid transaction
            val transaction =
                Transaction(
                    sourceAccountId = sourceAccountId,
                    targetAccountId = targetAccountId,
                    assetId = assetId,
                    amount = 100.0,
                    timestamp = now,
                )
            val transactionId = transactionRepository.createTransaction(transaction)

            // Verify transaction was created
            assertTrue(transactionId > 0, "Transaction ID should be positive, but was: $transactionId")

            // Attempt to update to invalid state (same source and target)
            val retrieved = transactionRepository.getTransactionById(transactionId).first()
            assertNotNull(retrieved, "Retrieved transaction should not be null for ID: $transactionId")

            val invalidUpdate = retrieved.copy(targetAccountId = sourceAccountId) // Same as source

            // Should throw exception due to CHECK constraint
            assertFailsWith<Exception> {
                transactionRepository.updateTransaction(invalidUpdate)
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
                    Account(name = "Checking", openingDate = now),
                )
            val savingsAccountId =
                accountRepository.createAccount(
                    Account(name = "Savings", openingDate = now),
                )

            // Create test asset
            val usdId = assetRepository.upsertAssetByName("USD")

            // Create transaction: Checking -> Savings, 100 USD
            val transaction =
                Transaction(
                    sourceAccountId = checkingAccountId,
                    targetAccountId = savingsAccountId,
                    assetId = usdId,
                    amount = 100.0,
                    timestamp = now,
                )
            transactionRepository.createTransaction(transaction)

            // Get balances
            val balances = transactionRepository.getAccountBalances().first()

            // Find balances for each account
            val checkingBalance = balances.find { it.accountId == checkingAccountId && it.assetId == usdId }
            val savingsBalance = balances.find { it.accountId == savingsAccountId && it.assetId == usdId }

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
                    Account(name = "Checking", openingDate = now),
                )
            val savingsAccountId =
                accountRepository.createAccount(
                    Account(name = "Savings", openingDate = now),
                )
            val creditCardAccountId =
                accountRepository.createAccount(
                    Account(name = "Credit Card", openingDate = now),
                )

            // Create test asset
            val usdId = assetRepository.upsertAssetByName("USD")

            // Create multiple transactions
            // 1. Checking -> Savings: 100
            transactionRepository.createTransaction(
                Transaction(
                    sourceAccountId = checkingAccountId,
                    targetAccountId = savingsAccountId,
                    assetId = usdId,
                    amount = 100.0,
                    timestamp = now,
                ),
            )

            // 2. Checking -> Credit Card: 50 (payment)
            transactionRepository.createTransaction(
                Transaction(
                    sourceAccountId = checkingAccountId,
                    targetAccountId = creditCardAccountId,
                    assetId = usdId,
                    amount = 50.0,
                    timestamp = now,
                ),
            )

            // 3. Savings -> Checking: 30
            transactionRepository.createTransaction(
                Transaction(
                    sourceAccountId = savingsAccountId,
                    targetAccountId = checkingAccountId,
                    assetId = usdId,
                    amount = 30.0,
                    timestamp = now,
                ),
            )

            // Get balances
            val balances = transactionRepository.getAccountBalances().first()

            // Find balances for each account
            val checkingBalance = balances.find { it.accountId == checkingAccountId && it.assetId == usdId }
            val savingsBalance = balances.find { it.accountId == savingsAccountId && it.assetId == usdId }
            val creditCardBalance = balances.find { it.accountId == creditCardAccountId && it.assetId == usdId }

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
    fun `getAccountBalances should handle multiple assets per account`() =
        runTest {
            val now = Clock.System.now()

            // Create test accounts
            val checkingAccountId =
                accountRepository.createAccount(
                    Account(name = "Checking", openingDate = now),
                )
            val savingsAccountId =
                accountRepository.createAccount(
                    Account(name = "Savings", openingDate = now),
                )

            // Create test assets
            val usdId = assetRepository.upsertAssetByName("USD")
            val eurId = assetRepository.upsertAssetByName("EUR")

            // Create transactions in different currencies
            // USD: Checking -> Savings, 100
            transactionRepository.createTransaction(
                Transaction(
                    sourceAccountId = checkingAccountId,
                    targetAccountId = savingsAccountId,
                    assetId = usdId,
                    amount = 100.0,
                    timestamp = now,
                ),
            )

            // EUR: Checking -> Savings, 50
            transactionRepository.createTransaction(
                Transaction(
                    sourceAccountId = checkingAccountId,
                    targetAccountId = savingsAccountId,
                    assetId = eurId,
                    amount = 50.0,
                    timestamp = now,
                ),
            )

            // Get balances
            val balances = transactionRepository.getAccountBalances().first()

            // Find balances for checking account
            val checkingUsdBalance = balances.find { it.accountId == checkingAccountId && it.assetId == usdId }
            val checkingEurBalance = balances.find { it.accountId == checkingAccountId && it.assetId == eurId }

            // Find balances for savings account
            val savingsUsdBalance = balances.find { it.accountId == savingsAccountId && it.assetId == usdId }
            val savingsEurBalance = balances.find { it.accountId == savingsAccountId && it.assetId == eurId }

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
            accountRepository.createAccount(Account(name = "Checking", openingDate = now))
            accountRepository.createAccount(Account(name = "Savings", openingDate = now))

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
                    Account(name = "Source Account", openingDate = now),
                )
            val targetAccountId =
                accountRepository.createAccount(
                    Account(name = "Target Account", openingDate = now),
                )

            // Create test asset
            val assetId = assetRepository.upsertAssetByName("USD")

            // Create transaction
            val transaction =
                Transaction(
                    sourceAccountId = sourceAccountId,
                    targetAccountId = targetAccountId,
                    assetId = assetId,
                    amount = 100.0,
                    timestamp = now,
                )
            val transactionId = transactionRepository.createTransaction(transaction)

            // Delete transaction
            transactionRepository.deleteTransaction(transactionId)

            val deleted = transactionRepository.getTransactionById(transactionId).first()
            assertEquals(null, deleted)
        }

    @Test
    fun `getAllTransactions should return all transactions ordered by timestamp descending`() =
        runTest {
            val now = Clock.System.now()

            // Create test accounts
            val sourceAccountId =
                accountRepository.createAccount(
                    Account(name = "Source Account", openingDate = now),
                )
            val targetAccountId =
                accountRepository.createAccount(
                    Account(name = "Target Account", openingDate = now),
                )

            // Create test asset
            val assetId = assetRepository.upsertAssetByName("USD")

            // Create multiple transactions with different timestamps
            val transaction1 =
                Transaction(
                    sourceAccountId = sourceAccountId,
                    targetAccountId = targetAccountId,
                    assetId = assetId,
                    amount = 100.0,
                    timestamp = now - kotlin.time.Duration.parse("1h"),
                )
            val transaction2 =
                Transaction(
                    sourceAccountId = sourceAccountId,
                    targetAccountId = targetAccountId,
                    assetId = assetId,
                    amount = 200.0,
                    timestamp = now,
                )

            transactionRepository.createTransaction(transaction1)
            transactionRepository.createTransaction(transaction2)

            val allTransactions = transactionRepository.getAllTransactions().first()
            assertEquals(2, allTransactions.size)

            // Verify descending order by timestamp
            assertTrue(allTransactions[0].timestamp >= allTransactions[1].timestamp)
        }
}
