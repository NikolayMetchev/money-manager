@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.repository

import com.moneymanager.database.DatabaseMaintenanceService
import com.moneymanager.database.RepositorySet
import com.moneymanager.di.AppComponent
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.domain.repository.TransactionRepository
import com.moneymanager.test.database.createTestAppComponentParams
import com.moneymanager.test.database.createTestDatabaseLocation
import com.moneymanager.test.database.deleteTestDatabase
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.time.Clock
import kotlin.uuid.Uuid

class TransactionRepositoryImplTest: DbTest() {
    @Test
    fun `createTransfer should insert transaction and transfer`() =
        runTest {
            val now = Clock.System.now()

            // Create test accounts
            val sourceAccountId =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Source Account", openingDate = now),
                )
            val targetAccountId =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Target Account", openingDate = now),
                )

            // Create test currency
            val currencyId = repositories.currencyRepository.upsertCurrencyByCode("USD", "US Dollar")
            val currency = repositories.currencyRepository.getCurrencyById(currencyId).first()!!

            // Create transfer
            val transferId = TransferId(Uuid.random())
            val transfer =
                Transfer(
                    id = transferId,
                    timestamp = now,
                    description = "Test transaction",
                    sourceAccountId = sourceAccountId,
                    targetAccountId = targetAccountId,
                    amount = Money.fromDisplayValue(100.0, currency),
                )
            repositories.transactionRepository.createTransfer(transfer)

            val retrieved = repositories.transactionRepository.getTransactionById(transferId.id).first()
            assertNotNull(retrieved, "Retrieved transaction should not be null for ID: $transferId")
            assertEquals(sourceAccountId, retrieved.sourceAccountId)
            assertEquals(targetAccountId, retrieved.targetAccountId)
            assertEquals(currencyId, retrieved.amount.currency.id)
            assertEquals(Money.fromDisplayValue(100.0, currency), retrieved.amount)
        }

    @Test
    fun `createTransfer should fail when source and target accounts are the same`() =
        runTest {
            val now = Clock.System.now()

            // Create test account
            val accountId =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Test Account", openingDate = now),
                )

            // Create test currency
            val currencyId = repositories.currencyRepository.upsertCurrencyByCode("USD", "US Dollar")
            val currency = repositories.currencyRepository.getCurrencyById(currencyId).first()!!

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
                        amount = Money.fromDisplayValue(100.0, currency),
                    )
                repositories.transactionRepository.createTransfer(transfer)
            }
        }

    @Test
    fun `updateTransfer should fail when changing to same source and target accounts`() =
        runTest {
            val now = Clock.System.now()

            // Create test accounts
            val sourceAccountId =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Source Account", openingDate = now),
                )
            val targetAccountId =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Target Account", openingDate = now),
                )

            // Create test currency
            val currencyId = repositories.currencyRepository.upsertCurrencyByCode("USD", "US Dollar")
            val currency = repositories.currencyRepository.getCurrencyById(currencyId).first()!!

            // Create valid transfer
            val transferId = TransferId(Uuid.random())
            val transfer =
                Transfer(
                    id = transferId,
                    timestamp = now,
                    description = "Test transaction",
                    sourceAccountId = sourceAccountId,
                    targetAccountId = targetAccountId,
                    amount = Money.fromDisplayValue(100.0, currency),
                )
            repositories.transactionRepository.createTransfer(transfer)

            // Verify transaction was created
            val created = repositories.transactionRepository.getTransactionById(transferId.id).first()
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
                        amount = Money.fromDisplayValue(100.0, currency),
                    )
                repositories.transactionRepository.updateTransfer(invalidTransfer)
            }
        }

    // BALANCE CALCULATION TESTS

    @Test
    fun `getAccountBalances should return correct balances after single transaction`() =
        runTest {
            val now = Clock.System.now()

            // Create test accounts
            val checkingAccountId =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Checking", openingDate = now),
                )
            val savingsAccountId =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Savings", openingDate = now),
                )

            // Create test currency
            val usdId = repositories.currencyRepository.upsertCurrencyByCode("USD", "US Dollar")
            val usd = repositories.currencyRepository.getCurrencyById(usdId).first()!!

            // Create transaction: Checking -> Savings, 100 USD
            val transfer =
                Transfer(
                    id = TransferId(Uuid.random()),
                    timestamp = now,
                    description = "Transfer to savings",
                    sourceAccountId = checkingAccountId,
                    targetAccountId = savingsAccountId,
                    amount = Money.fromDisplayValue(100.0, usd),
                )
            repositories.transactionRepository.createTransfer(transfer)

            // Refresh materialized views
            repositories.maintenanceService.refreshMaterializedViews()

            // Get balances
            val balances = repositories.transactionRepository.getAccountBalances().first()

            // Find balances for each account
            val checkingBalance = balances.find { it.accountId == checkingAccountId && it.balance.currency.id == usdId }
            val savingsBalance = balances.find { it.accountId == savingsAccountId && it.balance.currency.id == usdId }

            // Verify balances
            assertNotNull(checkingBalance, "Checking account should have a balance")
            assertNotNull(savingsBalance, "Savings account should have a balance")
            assertEquals(Money.fromDisplayValue(-100.0, usd), checkingBalance.balance, "Checking should have -100 (outgoing)")
            assertEquals(Money.fromDisplayValue(100.0, usd), savingsBalance.balance, "Savings should have +100 (incoming)")
        }

    @Test
    fun `getAccountBalances should return correct balances after multiple transactions`() =
        runTest {
            val now = Clock.System.now()

            // Create test accounts
            val checkingAccountId =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Checking", openingDate = now),
                )
            val savingsAccountId =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Savings", openingDate = now),
                )
            val creditCardAccountId =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Credit Card", openingDate = now),
                )

            // Create test currency
            val usdId = repositories.currencyRepository.upsertCurrencyByCode("USD", "US Dollar")
            val usd = repositories.currencyRepository.getCurrencyById(usdId).first()!!

            // Create multiple transactions
            // 1. Checking -> Savings: 100
            repositories.transactionRepository.createTransfer(
                Transfer(
                    id = TransferId(Uuid.random()),
                    timestamp = now,
                    description = "Transfer to savings",
                    sourceAccountId = checkingAccountId,
                    targetAccountId = savingsAccountId,
                    amount = Money.fromDisplayValue(100.0, usd),
                ),
            )

            // 2. Checking -> Credit Card: 50 (payment)
            repositories.transactionRepository.createTransfer(
                Transfer(
                    id = TransferId(Uuid.random()),
                    timestamp = now,
                    description = "Credit card payment",
                    sourceAccountId = checkingAccountId,
                    targetAccountId = creditCardAccountId,
                    amount = Money.fromDisplayValue(50.0, usd),
                ),
            )

            // 3. Savings -> Checking: 30
            repositories.transactionRepository.createTransfer(
                Transfer(
                    id = TransferId(Uuid.random()),
                    timestamp = now,
                    description = "Transfer from savings",
                    sourceAccountId = savingsAccountId,
                    targetAccountId = checkingAccountId,
                    amount = Money.fromDisplayValue(30.0, usd),
                ),
            )

            // Refresh materialized views
            repositories.maintenanceService.refreshMaterializedViews()

            // Get balances
            val balances = repositories.transactionRepository.getAccountBalances().first()

            // Find balances for each account
            val checkingBalance = balances.find { it.accountId == checkingAccountId && it.balance.currency.id == usdId }
            val savingsBalance = balances.find { it.accountId == savingsAccountId && it.balance.currency.id == usdId }
            val creditCardBalance = balances.find { it.accountId == creditCardAccountId && it.balance.currency.id == usdId }

            // Verify balances
            assertNotNull(checkingBalance)
            assertNotNull(savingsBalance)
            assertNotNull(creditCardBalance)

            // Checking: -100 (out) - 50 (out) + 30 (in) = -120
            assertEquals(Money.fromDisplayValue(-120.0, usd), checkingBalance.balance, "Checking balance should be -120")

            // Savings: +100 (in) - 30 (out) = +70
            assertEquals(Money.fromDisplayValue(70.0, usd), savingsBalance.balance, "Savings balance should be +70")

            // Credit Card: +50 (in) = +50
            assertEquals(Money.fromDisplayValue(50.0, usd), creditCardBalance.balance, "Credit Card balance should be +50")
        }

    @Test
    fun `getAccountBalances should handle multiple currencies per account`() =
        runTest {
            val now = Clock.System.now()

            // Create test accounts
            val checkingAccountId =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Checking", openingDate = now),
                )
            val savingsAccountId =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Savings", openingDate = now),
                )

            // Create test currencies
            val usdId = repositories.currencyRepository.upsertCurrencyByCode("USD", "US Dollar")
            val usd = repositories.currencyRepository.getCurrencyById(usdId).first()!!
            val eurId = repositories.currencyRepository.upsertCurrencyByCode("EUR", "Euro")
            val eur = repositories.currencyRepository.getCurrencyById(eurId).first()!!

            // Create transactions in different currencies
            // USD: Checking -> Savings, 100
            repositories.transactionRepository.createTransfer(
                Transfer(
                    id = TransferId(Uuid.random()),
                    timestamp = now,
                    description = "Transfer USD to savings",
                    sourceAccountId = checkingAccountId,
                    targetAccountId = savingsAccountId,
                    amount = Money.fromDisplayValue(100.0, usd),
                ),
            )

            // EUR: Checking -> Savings, 50
            repositories.transactionRepository.createTransfer(
                Transfer(
                    id = TransferId(Uuid.random()),
                    timestamp = now,
                    description = "Transfer EUR to savings",
                    sourceAccountId = checkingAccountId,
                    targetAccountId = savingsAccountId,
                    amount = Money.fromDisplayValue(50.0, eur),
                ),
            )

            // Refresh materialized views
            repositories.maintenanceService.refreshMaterializedViews()

            // Get balances
            val balances = repositories.transactionRepository.getAccountBalances().first()

            // Find balances for checking account
            val checkingUsdBalance = balances.find { it.accountId == checkingAccountId && it.balance.currency.id == usdId }
            val checkingEurBalance = balances.find { it.accountId == checkingAccountId && it.balance.currency.id == eurId }

            // Find balances for savings account
            val savingsUsdBalance = balances.find { it.accountId == savingsAccountId && it.balance.currency.id == usdId }
            val savingsEurBalance = balances.find { it.accountId == savingsAccountId && it.balance.currency.id == eurId }

            // Verify balances
            assertNotNull(checkingUsdBalance)
            assertNotNull(checkingEurBalance)
            assertNotNull(savingsUsdBalance)
            assertNotNull(savingsEurBalance)

            assertEquals(Money.fromDisplayValue(-100.0, usd), checkingUsdBalance.balance, "Checking USD balance should be -100")
            assertEquals(Money.fromDisplayValue(-50.0, eur), checkingEurBalance.balance, "Checking EUR balance should be -50")
            assertEquals(Money.fromDisplayValue(100.0, usd), savingsUsdBalance.balance, "Savings USD balance should be +100")
            assertEquals(Money.fromDisplayValue(50.0, eur), savingsEurBalance.balance, "Savings EUR balance should be +50")
        }

    @Test
    fun `getAccountBalances should return empty list when no transactions exist`() =
        runTest {
            // Create accounts but no transactions
            val now = Clock.System.now()
            repositories.accountRepository.createAccount(Account(id = AccountId(0), name = "Checking", openingDate = now))
            repositories.accountRepository.createAccount(Account(id = AccountId(0), name = "Savings", openingDate = now))

            val balances = repositories.transactionRepository.getAccountBalances().first()

            assertEquals(0, balances.size, "Should have no balances when no transactions exist")
        }

    // OTHER TRANSACTION TESTS

    @Test
    fun `deleteTransaction should remove transaction`() =
        runTest {
            val now = Clock.System.now()

            // Create test accounts
            val sourceAccountId =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Source Account", openingDate = now),
                )
            val targetAccountId =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Target Account", openingDate = now),
                )

            // Create test currency
            val currencyId = repositories.currencyRepository.upsertCurrencyByCode("USD", "US Dollar")
            val currency = repositories.currencyRepository.getCurrencyById(currencyId).first()!!

            // Create transaction
            val transferId = TransferId(Uuid.random())
            val transfer =
                Transfer(
                    id = transferId,
                    timestamp = now,
                    description = "Test transaction to delete",
                    sourceAccountId = sourceAccountId,
                    targetAccountId = targetAccountId,
                    amount = Money.fromDisplayValue(100.0, currency),
                )
            repositories.transactionRepository.createTransfer(transfer)

            // Delete transaction
            repositories.transactionRepository.deleteTransaction(transferId.id)

            val deleted = repositories.transactionRepository.getTransactionById(transferId.id).first()
            assertEquals(null, deleted)
        }
}
