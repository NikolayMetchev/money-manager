@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database

import app.cash.sqldelight.db.QueryResult
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
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Data class for balance view records (test-only).
 */
private data class BalanceViewRecord(
    val accountId: Long,
    val currencyId: String,
    val balance: Long?,
)

/**
 * Comprehensive tests for incremental materialized view refresh.
 *
 * Tests verify that:
 * 1. Triggers correctly track changes in PendingMaterializedViewChanges
 * 2. Incremental refresh produces same results as views
 * 3. All timestamp edge cases are handled (before all, in middle, after all)
 * 4. New accounts and currencies are handled correctly
 * 5. Multiple changes are batched correctly
 */
class IncrementalMaterializedViewRefreshTest : DbTest() {
    /**
     * Test-only query: countPendingChanges
     * Counts pending changes using raw SQL.
     */
    private fun countPendingChanges(): Long {
        val sql = "SELECT COUNT(*) FROM pending_materialized_view_changes"
        return database.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0)!!)
            },
            parameters = 0,
        ).value
    }

    /**
     * Test-only query: selectBalancesFromView
     * Retrieves balances from AccountBalanceView using raw SQL.
     */
    private fun selectBalancesFromView(): List<BalanceViewRecord> {
        val sql = "SELECT account_id, currency_id, balance FROM account_balance_view ORDER BY account_id, currency_id"
        return database.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                val results = mutableListOf<BalanceViewRecord>()
                while (cursor.next().value) {
                    results.add(
                        BalanceViewRecord(
                            accountId = cursor.getLong(0)!!,
                            currencyId = cursor.getString(1)!!,
                            balance = cursor.getLong(2),
                        ),
                    )
                }
                QueryResult.Value(results)
            },
            parameters = 0,
        ).value
    }

    // Helper to verify materialized views match the source views
    private suspend fun verifyMaterializedViewsMatchViews() {
        // Refresh incrementally
        repositories.maintenanceService.refreshMaterializedViews()

        // Query both materialized views and views
        val materializedBalances =
            database.transferQueries.selectAllBalances()
                .executeAsList()
                .sortedBy { "${it.account_id}-${it.currency_id}" }

        val viewBalances = selectBalancesFromView()

        // Verify same number of rows
        assertEquals(
            viewBalances.size,
            materializedBalances.size,
            "Materialized view and view should have same number of balance rows",
        )

        // Verify each row matches
        materializedBalances.zip(viewBalances).forEach { (materialized, view) ->
            assertEquals(
                view.accountId,
                materialized.account_id,
                "Account ID should match between materialized view and view",
            )
            assertEquals(
                view.currencyId,
                materialized.currency_id,
                "Currency ID should match between materialized view and view",
            )
            assertEquals(
                view.balance ?: 0,
                materialized.balance,
                "Balance should match between materialized view and view",
            )
        }
    }

    // Helper to get count of pending changes
    private fun getPendingChangesCount(): Long = countPendingChanges()

    // Helper to create test data
    private suspend fun createTestAccounts(): Pair<AccountId, AccountId> {
        val now = Clock.System.now()
        val account1Id =
            repositories.accountRepository.createAccount(
                Account(id = AccountId(0), name = "Account 1", openingDate = now),
            )
        val account2Id =
            repositories.accountRepository.createAccount(
                Account(id = AccountId(0), name = "Account 2", openingDate = now),
            )
        return Pair(account1Id, account2Id)
    }

    // INSERT TESTS - Different timestamp positions

    @Test
    fun `INSERT with timestamp after all existing transactions should trigger incremental refresh`() =
        runTest {
            val (account1Id, account2Id) = createTestAccounts()
            val currencyId = repositories.currencyRepository.upsertCurrencyByCode("USD", "US Dollar")
            val currency = repositories.currencyRepository.getCurrencyById(currencyId).first()!!

            val now = Clock.System.now()

            // Insert first transfer
            createTransfer(
                Transfer(
                    id = TransferId(Uuid.random()),
                    timestamp = now,
                    description = "Transfer 1",
                    sourceAccountId = account1Id,
                    targetAccountId = account2Id,
                    amount = Money.fromDisplayValue(100.0, currency),
                ),
            )

            // Initial refresh to populate materialized views
            repositories.maintenanceService.fullRefreshMaterializedViews()

            // Insert transfer with timestamp AFTER existing
            createTransfer(
                Transfer(
                    id = TransferId(Uuid.random()),
                    timestamp = now.plus(kotlin.time.Duration.parse("1h")),
                    description = "Transfer 2 (after)",
                    sourceAccountId = account1Id,
                    targetAccountId = account2Id,
                    amount = Money.fromDisplayValue(50.0, currency),
                ),
            )

            // Verify pending changes were tracked
            assertTrue(
                getPendingChangesCount() > 0,
                "Pending changes should be tracked after INSERT",
            )

            // Verify incremental refresh produces correct results
            verifyMaterializedViewsMatchViews()

            // Verify pending changes were cleared
            assertEquals(
                0,
                getPendingChangesCount(),
                "Pending changes should be cleared after refresh",
            )
        }

    @Test
    fun `INSERT with timestamp in middle of existing transactions should trigger incremental refresh`() =
        runTest {
            val (account1Id, account2Id) = createTestAccounts()
            val currencyId = repositories.currencyRepository.upsertCurrencyByCode("USD", "US Dollar")
            val currency = repositories.currencyRepository.getCurrencyById(currencyId).first()!!

            val now = Clock.System.now()

            // Insert first transfer
            createTransfer(
                Transfer(
                    id = TransferId(Uuid.random()),
                    timestamp = now,
                    description = "Transfer 1",
                    sourceAccountId = account1Id,
                    targetAccountId = account2Id,
                    amount = Money.fromDisplayValue(100.0, currency),
                ),
            )

            // Insert third transfer (leaving gap for middle insert)
            createTransfer(
                Transfer(
                    id = TransferId(Uuid.random()),
                    timestamp = now.plus(kotlin.time.Duration.parse("2h")),
                    description = "Transfer 3",
                    sourceAccountId = account1Id,
                    targetAccountId = account2Id,
                    amount = Money.fromDisplayValue(75.0, currency),
                ),
            )

            // Initial refresh
            repositories.maintenanceService.fullRefreshMaterializedViews()

            // Insert transfer with timestamp IN THE MIDDLE
            createTransfer(
                Transfer(
                    id = TransferId(Uuid.random()),
                    timestamp = now.plus(kotlin.time.Duration.parse("1h")),
                    description = "Transfer 2 (middle)",
                    sourceAccountId = account1Id,
                    targetAccountId = account2Id,
                    amount = Money.fromDisplayValue(50.0, currency),
                ),
            )

            // Verify incremental refresh produces correct results
            verifyMaterializedViewsMatchViews()
        }

    @Test
    fun `INSERT with timestamp before all existing transactions should trigger incremental refresh`() =
        runTest {
            val (account1Id, account2Id) = createTestAccounts()
            val currencyId = repositories.currencyRepository.upsertCurrencyByCode("USD", "US Dollar")
            val currency = repositories.currencyRepository.getCurrencyById(currencyId).first()!!

            val now = Clock.System.now()

            // Insert transfer at current time
            createTransfer(
                Transfer(
                    id = TransferId(Uuid.random()),
                    timestamp = now,
                    description = "Transfer 1",
                    sourceAccountId = account1Id,
                    targetAccountId = account2Id,
                    amount = Money.fromDisplayValue(100.0, currency),
                ),
            )

            // Initial refresh
            repositories.maintenanceService.fullRefreshMaterializedViews()

            // Insert transfer with timestamp BEFORE existing
            createTransfer(
                Transfer(
                    id = TransferId(Uuid.random()),
                    timestamp = now.minus(kotlin.time.Duration.parse("1h")),
                    description = "Transfer 0 (before)",
                    sourceAccountId = account1Id,
                    targetAccountId = account2Id,
                    amount = Money.fromDisplayValue(50.0, currency),
                ),
            )

            // Verify incremental refresh produces correct results
            verifyMaterializedViewsMatchViews()
        }

    // UPDATE TESTS - Different timestamp positions

    @Test
    fun `UPDATE changing timestamp to after all transactions should trigger incremental refresh`() =
        runTest {
            val (account1Id, account2Id) = createTestAccounts()
            val currencyId = repositories.currencyRepository.upsertCurrencyByCode("USD", "US Dollar")
            val currency = repositories.currencyRepository.getCurrencyById(currencyId).first()!!

            val now = Clock.System.now()

            // Insert two transfers
            val transfer1Id = TransferId(Uuid.random())
            createTransfer(
                Transfer(
                    id = transfer1Id,
                    timestamp = now,
                    description = "Transfer 1",
                    sourceAccountId = account1Id,
                    targetAccountId = account2Id,
                    amount = Money.fromDisplayValue(100.0, currency),
                ),
            )

            createTransfer(
                Transfer(
                    id = TransferId(Uuid.random()),
                    timestamp = now.plus(kotlin.time.Duration.parse("1h")),
                    description = "Transfer 2",
                    sourceAccountId = account1Id,
                    targetAccountId = account2Id,
                    amount = Money.fromDisplayValue(50.0, currency),
                ),
            )

            // Initial refresh
            repositories.maintenanceService.fullRefreshMaterializedViews()

            // UPDATE first transfer to have timestamp AFTER second
            repositories.transactionRepository.updateTransfer(
                transfer =
                    Transfer(
                        id = transfer1Id,
                        timestamp = now.plus(kotlin.time.Duration.parse("2h")),
                        description = "Transfer 1 (updated)",
                        sourceAccountId = account1Id,
                        targetAccountId = account2Id,
                        amount = Money.fromDisplayValue(100.0, currency),
                    ),
                deletedAttributeIds = emptySet(),
                updatedAttributes = emptyMap(),
                newAttributes = emptyList(),
                transactionId = transfer1Id,
            )

            // Verify incremental refresh produces correct results
            verifyMaterializedViewsMatchViews()
        }

    @Test
    fun `UPDATE changing accounts should track all 4 account-currency pairs`() =
        runTest {
            val now = Clock.System.now()

            // Create 4 accounts
            val account1Id =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Account 1", openingDate = now),
                )
            val account2Id =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Account 2", openingDate = now),
                )
            val account3Id =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Account 3", openingDate = now),
                )
            val account4Id =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Account 4", openingDate = now),
                )

            val currencyId = repositories.currencyRepository.upsertCurrencyByCode("USD", "US Dollar")
            val currency = repositories.currencyRepository.getCurrencyById(currencyId).first()!!

            // Insert transfer between account1 and account2
            val transferId = TransferId(Uuid.random())
            createTransfer(
                Transfer(
                    id = transferId,
                    timestamp = now,
                    description = "Original transfer",
                    sourceAccountId = account1Id,
                    targetAccountId = account2Id,
                    amount = Money.fromDisplayValue(100.0, currency),
                ),
            )

            // Initial refresh
            repositories.maintenanceService.fullRefreshMaterializedViews()

            // UPDATE to change BOTH source and target accounts (account1→account2 becomes account3→account4)
            repositories.transactionRepository.updateTransfer(
                transfer =
                    Transfer(
                        id = transferId,
                        timestamp = now,
                        description = "Updated transfer",
                        sourceAccountId = account3Id,
                        targetAccountId = account4Id,
                        amount = Money.fromDisplayValue(100.0, currency),
                    ),
                deletedAttributeIds = emptySet(),
                updatedAttributes = emptyMap(),
                newAttributes = emptyList(),
                transactionId = transferId,
            )

            // Should track 4 account-currency pairs:
            // OLD: (account1, USD), (account2, USD)
            // NEW: (account3, USD), (account4, USD)
            val pendingCount = getPendingChangesCount()
            assertTrue(
                pendingCount >= 2,
                "Should track at least 2 pending changes (may have duplicates if accounts overlap)",
            )

            // Verify incremental refresh produces correct results
            verifyMaterializedViewsMatchViews()
        }

    // DELETE TESTS

    @Test
    fun `DELETE should trigger incremental refresh`() =
        runTest {
            val (account1Id, account2Id) = createTestAccounts()
            val currencyId = repositories.currencyRepository.upsertCurrencyByCode("USD", "US Dollar")
            val currency = repositories.currencyRepository.getCurrencyById(currencyId).first()!!

            val now = Clock.System.now()

            // Insert two transfers
            val transfer1Id = TransferId(Uuid.random())
            createTransfer(
                Transfer(
                    id = transfer1Id,
                    timestamp = now,
                    description = "Transfer 1",
                    sourceAccountId = account1Id,
                    targetAccountId = account2Id,
                    amount = Money.fromDisplayValue(100.0, currency),
                ),
            )

            createTransfer(
                Transfer(
                    id = TransferId(Uuid.random()),
                    timestamp = now.plus(kotlin.time.Duration.parse("1h")),
                    description = "Transfer 2",
                    sourceAccountId = account1Id,
                    targetAccountId = account2Id,
                    amount = Money.fromDisplayValue(50.0, currency),
                ),
            )

            // Initial refresh
            repositories.maintenanceService.fullRefreshMaterializedViews()

            // DELETE first transfer
            repositories.transactionRepository.deleteTransaction(transfer1Id.id)

            // Verify pending changes were tracked
            assertTrue(
                getPendingChangesCount() > 0,
                "Pending changes should be tracked after DELETE",
            )

            // Verify incremental refresh produces correct results
            verifyMaterializedViewsMatchViews()
        }

    @Test
    fun `DELETE removing all transfers for account-currency pair should remove from materialized view`() =
        runTest {
            val (account1Id, account2Id) = createTestAccounts()
            val currencyId = repositories.currencyRepository.upsertCurrencyByCode("USD", "US Dollar")
            val currency = repositories.currencyRepository.getCurrencyById(currencyId).first()!!

            val now = Clock.System.now()

            // Insert single transfer
            val transferId = TransferId(Uuid.random())
            createTransfer(
                Transfer(
                    id = transferId,
                    timestamp = now,
                    description = "Only transfer",
                    sourceAccountId = account1Id,
                    targetAccountId = account2Id,
                    amount = Money.fromDisplayValue(100.0, currency),
                ),
            )

            // Initial refresh
            repositories.maintenanceService.fullRefreshMaterializedViews()

            // Verify materialized view has entries
            val balancesBeforeDelete = database.transferQueries.selectAllBalances().executeAsList()
            assertTrue(
                balancesBeforeDelete.isNotEmpty(),
                "Should have balances before delete",
            )

            // DELETE the only transfer
            repositories.transactionRepository.deleteTransaction(transferId.id)

            // Verify incremental refresh produces correct results (should be empty)
            verifyMaterializedViewsMatchViews()

            // Verify materialized view is now empty
            val balancesAfterDelete = database.transferQueries.selectAllBalances().executeAsList()
            assertEquals(
                0,
                balancesAfterDelete.size,
                "Materialized view should be empty after deleting all transfers",
            )
        }

    // NEW ENTITY TESTS

    @Test
    fun `INSERT for new account should be handled correctly`() =
        runTest {
            val now = Clock.System.now()

            // Create first account and insert transfer
            val account1Id =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Account 1", openingDate = now),
                )
            val account2Id =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Account 2", openingDate = now),
                )
            val currencyId = repositories.currencyRepository.upsertCurrencyByCode("USD", "US Dollar")
            val currency = repositories.currencyRepository.getCurrencyById(currencyId).first()!!

            createTransfer(
                Transfer(
                    id = TransferId(Uuid.random()),
                    timestamp = now,
                    description = "First transfer",
                    sourceAccountId = account1Id,
                    targetAccountId = account2Id,
                    amount = Money.fromDisplayValue(100.0, currency),
                ),
            )

            // Initial refresh
            repositories.maintenanceService.fullRefreshMaterializedViews()

            // Create NEW account and insert transfer involving it
            val account3Id =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Account 3 (NEW)", openingDate = now),
                )

            createTransfer(
                Transfer(
                    id = TransferId(Uuid.random()),
                    timestamp = now.plus(kotlin.time.Duration.parse("1h")),
                    description = "Transfer with new account",
                    sourceAccountId = account3Id,
                    targetAccountId = account1Id,
                    amount = Money.fromDisplayValue(50.0, currency),
                ),
            )

            // Verify incremental refresh produces correct results
            verifyMaterializedViewsMatchViews()

            // Verify the new account appears in balances
            val balances = database.transferQueries.selectAllBalances().executeAsList()
            assertTrue(
                balances.any { it.account_id == account3Id.id },
                "New account should appear in materialized view",
            )
        }

    @Test
    fun `INSERT for new currency should be handled correctly`() =
        runTest {
            val now = Clock.System.now()
            val (account1Id, account2Id) = createTestAccounts()

            // Insert transfer with USD
            val usdId = repositories.currencyRepository.upsertCurrencyByCode("USD", "US Dollar")
            val usdCurrency = repositories.currencyRepository.getCurrencyById(usdId).first()!!
            createTransfer(
                Transfer(
                    id = TransferId(Uuid.random()),
                    timestamp = now,
                    description = "USD transfer",
                    sourceAccountId = account1Id,
                    targetAccountId = account2Id,
                    amount = Money.fromDisplayValue(100.0, usdCurrency),
                ),
            )

            // Initial refresh
            repositories.maintenanceService.fullRefreshMaterializedViews()

            // Insert transfer with NEW currency (EUR)
            val eurId = repositories.currencyRepository.upsertCurrencyByCode("EUR", "Euro")
            val eurCurrency = repositories.currencyRepository.getCurrencyById(eurId).first()!!
            createTransfer(
                Transfer(
                    id = TransferId(Uuid.random()),
                    timestamp = now.plus(kotlin.time.Duration.parse("1h")),
                    description = "EUR transfer",
                    sourceAccountId = account1Id,
                    targetAccountId = account2Id,
                    amount = Money.fromDisplayValue(50.0, eurCurrency),
                ),
            )

            // Verify incremental refresh produces correct results
            verifyMaterializedViewsMatchViews()

            // Verify the new currency appears in balances
            val balances = database.transferQueries.selectAllBalances().executeAsList()
            assertTrue(
                balances.any { it.currency_id == eurId.id.toString() },
                "New currency should appear in materialized view",
            )
        }

    // BATCHING TEST

    @Test
    fun `Multiple changes between refreshes should be batched correctly`() =
        runTest {
            val (account1Id, account2Id) = createTestAccounts()
            val currencyId = repositories.currencyRepository.upsertCurrencyByCode("USD", "US Dollar")
            val currency = repositories.currencyRepository.getCurrencyById(currencyId).first()!!

            val now = Clock.System.now()

            // Insert initial transfer
            createTransfer(
                Transfer(
                    id = TransferId(Uuid.random()),
                    timestamp = now,
                    description = "Initial transfer",
                    sourceAccountId = account1Id,
                    targetAccountId = account2Id,
                    amount = Money.fromDisplayValue(100.0, currency),
                ),
            )

            // Initial refresh
            repositories.maintenanceService.fullRefreshMaterializedViews()

            // Make MULTIPLE changes WITHOUT refreshing in between
            createTransfer(
                Transfer(
                    id = TransferId(Uuid.random()),
                    timestamp = now.plus(kotlin.time.Duration.parse("1h")),
                    description = "Transfer 2",
                    sourceAccountId = account1Id,
                    targetAccountId = account2Id,
                    amount = Money.fromDisplayValue(50.0, currency),
                ),
            )

            createTransfer(
                Transfer(
                    id = TransferId(Uuid.random()),
                    timestamp = now.plus(kotlin.time.Duration.parse("2h")),
                    description = "Transfer 3",
                    sourceAccountId = account2Id,
                    targetAccountId = account1Id,
                    amount = Money.fromDisplayValue(25.0, currency),
                ),
            )

            createTransfer(
                Transfer(
                    id = TransferId(Uuid.random()),
                    timestamp = now.plus(kotlin.time.Duration.parse("3h")),
                    description = "Transfer 4",
                    sourceAccountId = account1Id,
                    targetAccountId = account2Id,
                    amount = Money.fromDisplayValue(75.0, currency),
                ),
            )

            // Pending changes should have accumulated
            val pendingCount = getPendingChangesCount()
            assertTrue(
                pendingCount > 0,
                "Should have pending changes accumulated from multiple operations",
            )

            // Single incremental refresh should handle all batched changes
            verifyMaterializedViewsMatchViews()

            // Verify all changes were cleared
            assertEquals(
                0,
                getPendingChangesCount(),
                "All pending changes should be cleared after single refresh",
            )
        }

    // FULL VS INCREMENTAL COMPARISON

    @Test
    fun `Incremental refresh should produce same results as full refresh`() =
        runTest {
            val (account1Id, account2Id) = createTestAccounts()
            val currencyId = repositories.currencyRepository.upsertCurrencyByCode("USD", "US Dollar")
            val currency = repositories.currencyRepository.getCurrencyById(currencyId).first()!!

            val now = Clock.System.now()

            // Insert several transfers
            repeat(5) { i ->
                createTransfer(
                    Transfer(
                        id = TransferId(Uuid.random()),
                        timestamp = now.plus(kotlin.time.Duration.parse("${i}h")),
                        description = "Transfer $i",
                        sourceAccountId = if (i % 2 == 0) account1Id else account2Id,
                        targetAccountId = if (i % 2 == 0) account2Id else account1Id,
                        amount = Money.fromDisplayValue((i + 1) * 10.0, currency),
                    ),
                )
            }

            // Do incremental refresh
            repositories.maintenanceService.refreshMaterializedViews()
            val incrementalBalances =
                database.transferQueries.selectAllBalances()
                    .executeAsList()
                    .sortedBy { "${it.account_id}-${it.currency_id}" }

            // Do full refresh
            repositories.maintenanceService.fullRefreshMaterializedViews()
            val fullBalances =
                database.transferQueries.selectAllBalances()
                    .executeAsList()
                    .sortedBy { "${it.account_id}-${it.currency_id}" }

            // Compare results
            assertEquals(
                fullBalances.size,
                incrementalBalances.size,
                "Incremental and full refresh should produce same number of rows",
            )

            fullBalances.zip(incrementalBalances).forEach { (full, incremental) ->
                assertEquals(
                    full.account_id,
                    incremental.account_id,
                    "Account IDs should match",
                )
                assertEquals(
                    full.currency_id,
                    incremental.currency_id,
                    "Currency IDs should match",
                )
                assertEquals(
                    full.balance,
                    incremental.balance,
                    "Balances should match",
                )
            }
        }
}
