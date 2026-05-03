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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class DeclinedTransactionRepositoryImplTest : DbTest() {
    @Test
    fun `insert and getById should store and retrieve a declined transaction`() =
        runTest {
            val transactionId = insertTransactionId()

            repositories.declinedTransactionRepository.insert(transactionId, "Insufficient funds")

            val result = repositories.declinedTransactionRepository.getById(transactionId)
            assertNotNull(result, "Declined transaction should be retrievable")
            assertEquals(transactionId, result.transactionId)
            assertEquals("Insufficient funds", result.declineReason)
        }

    @Test
    fun `getById should return null for non-existent transaction`() =
        runTest {
            val result = repositories.declinedTransactionRepository.getById(999L)
            assertNull(result, "Should return null for unknown transaction ID")
        }

    @Test
    fun `isDeclined should return true for a declined transaction`() =
        runTest {
            val transactionId = insertTransactionId()
            repositories.declinedTransactionRepository.insert(transactionId, "Card blocked")

            assertTrue(repositories.declinedTransactionRepository.isDeclined(transactionId))
        }

    @Test
    fun `isDeclined should return false for a non-declined transaction`() =
        runTest {
            assertFalse(repositories.declinedTransactionRepository.isDeclined(999L))
        }

    @Test
    fun `delete should remove a declined transaction`() =
        runTest {
            val transactionId = insertTransactionId()
            repositories.declinedTransactionRepository.insert(transactionId, "Card expired")

            repositories.declinedTransactionRepository.delete(transactionId)

            assertNull(repositories.declinedTransactionRepository.getById(transactionId))
            assertFalse(repositories.declinedTransactionRepository.isDeclined(transactionId))
        }

    @Test
    fun `getAll should return all declined transactions`() =
        runTest {
            val id1 = insertTransactionId()
            val id2 = insertTransactionId()

            repositories.declinedTransactionRepository.insert(id1, "Reason 1")
            repositories.declinedTransactionRepository.insert(id2, "Reason 2")

            val all = repositories.declinedTransactionRepository.getAll().first()
            assertEquals(2, all.size)
            assertTrue(all.any { it.transactionId == id1 && it.declineReason == "Reason 1" })
            assertTrue(all.any { it.transactionId == id2 && it.declineReason == "Reason 2" })
        }

    @Test
    fun `getAccountBalances should exclude declined transactions`() =
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

            val usdId = repositories.currencyRepository.upsertCurrencyByCode("USD", "US Dollar")
            val usd = repositories.currencyRepository.getCurrencyById(usdId).first()!!

            // Create two transfers
            val transfer1 =
                Transfer(
                    id = TransferId(0L),
                    timestamp = now,
                    description = "Normal transfer",
                    sourceAccountId = sourceAccountId,
                    targetAccountId = targetAccountId,
                    amount = Money.fromDisplayValue("100", usd),
                )
            val transfer2 =
                Transfer(
                    id = TransferId(0L),
                    timestamp = now,
                    description = "Declined transfer",
                    sourceAccountId = sourceAccountId,
                    targetAccountId = targetAccountId,
                    amount = Money.fromDisplayValue("50", usd),
                )

            val created1 = createTransfer(transfer1)
            val created2 = createTransfer(transfer2)

            // Mark the second transfer as declined
            repositories.declinedTransactionRepository.insert(created2.id.id, "Insufficient funds")

            // Refresh materialized views
            repositories.maintenanceService.refreshMaterializedViews()

            // Get balances - only the first transfer should be included
            val balances = repositories.transactionRepository.getAccountBalances().first()

            val sourceBalance = balances.find { it.accountId == sourceAccountId && it.balance.currency.id == usdId }
            val targetBalance = balances.find { it.accountId == targetAccountId && it.balance.currency.id == usdId }

            assertNotNull(sourceBalance, "Source account should have a balance")
            assertNotNull(targetBalance, "Target account should have a balance")
            assertEquals(
                Money.fromDisplayValue("-100", usd),
                sourceBalance.balance,
                "Source balance should only include non-declined transfers",
            )
            assertEquals(
                Money.fromDisplayValue("100", usd),
                targetBalance.balance,
                "Target balance should only include non-declined transfers",
            )
        }

    /**
     * Helper to insert a raw transaction_id row, returning the generated ID.
     * This simulates the allocation of a transaction ID without creating a transfer,
     * which is needed to test declined transactions for transactions that may not
     * have a corresponding transfer record.
     */
    private fun insertTransactionId(): Long {
        database.transactionIdQueries.insert()
        return database.transactionIdQueries.lastInsertedId().executeAsOne()
    }
}
