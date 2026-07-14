@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database

import app.cash.sqldelight.db.QueryResult
import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.createAccount
import com.moneymanager.test.database.createTestDatabaseLocation
import com.moneymanager.test.database.createTestDatabaseManager
import com.moneymanager.test.database.deleteTestDatabase
import com.moneymanager.test.database.upsertCurrencyByCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Verifies the database "shrink" and snapshot/restore primitives that back remote (Google Drive)
 * storage: truncating materialized views and exporting/importing a consistent database file.
 */
class DatabaseSnapshotAndShrinkTest : DbTest() {
    private fun MoneyManagerDatabaseWrapper.countRows(table: String): Long =
        executeQuery(
            identifier = null,
            sql = "SELECT COUNT(*) FROM $table",
            mapper = { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0)!!)
            },
            parameters = 0,
        ).value

    private suspend fun seedTransfer() {
        val account1 =
            repositories.accountRepository.createAccount(Account(id = AccountId(0), name = "From", openingDate = Clock.System.now()))
        val account2 =
            repositories.accountRepository.createAccount(Account(id = AccountId(0), name = "To", openingDate = Clock.System.now()))
        val currencyId = repositories.currencyRepository.upsertCurrencyByCode("USD", "US Dollar")
        val currency = repositories.currencyRepository.getCurrencyById(currencyId).first()!!
        createTransfer(
            Transfer(
                id = TransferId(0L),
                timestamp = Clock.System.now(),
                description = "Snapshot seed",
                sourceAccountId = account1,
                targetAccountId = account2,
                amount = Money.fromDisplayValue("100", currency),
            ),
        )
    }

    @Test
    fun truncateEmptiesMaterializedViewsAndFullRefreshRebuilds() =
        runTest {
            seedTransfer()
            repositories.maintenanceService.fullRefreshMaterializedViews()
            assertTrue(database.countRows("account_balance_materialized_view") > 0, "MV should be populated")
            assertTrue(database.countRows("running_balance_materialized_view") > 0, "running MV should be populated")

            repositories.maintenanceService.truncateMaterializedViews()
            assertEquals(0, database.countRows("account_balance_materialized_view"))
            assertEquals(0, database.countRows("running_balance_materialized_view"))
            assertEquals(0, database.countRows("pending_materialized_view_changes"))

            repositories.maintenanceService.fullRefreshMaterializedViews()
            assertTrue(database.countRows("account_balance_materialized_view") > 0, "MV should rebuild")
            assertTrue(database.countRows("running_balance_materialized_view") > 0, "running MV should rebuild")
        }

    @Test
    fun dataChangeTokenTracksLogicalChangesButNotViewRebuilds() =
        runTest {
            val before = database.dataChangeToken()
            repositories.accountRepository.createAccount(
                Account(id = AccountId(0), name = "Tracked", openingDate = Clock.System.now()),
            )
            val afterChange = database.dataChangeToken()
            assertTrue(afterChange > before, "creating an account should advance the change token")

            // Rebuilding materialized views is not a logical change and must not move the token.
            repositories.maintenanceService.fullRefreshMaterializedViews()
            assertEquals(afterChange, database.dataChangeToken(), "view rebuild must not change the token")
        }

    @Test
    fun snapshotRestoreRoundTripPreservesData() =
        runTest {
            seedTransfer()
            val manager = createTestDatabaseManager()

            val bytes = manager.snapshot(database)
            assertTrue(bytes.isNotEmpty(), "snapshot should produce bytes")

            val target = createTestDatabaseLocation()
            try {
                manager.restore(target, bytes)
                val restored = manager.openDatabase(target)
                try {
                    assertEquals(2, restored.countRows("account"), "accounts should survive the round trip")
                    assertEquals(1, restored.countRows("transfer"), "transfer should survive the round trip")

                    // Materialized views are truncated in real uploads; ensure a rehydrated DB can rebuild them.
                    DatabaseMaintenanceServiceImpl(restored).fullRefreshMaterializedViews()
                    assertTrue(restored.countRows("account_balance_materialized_view") > 0, "MV should rebuild after restore")
                } finally {
                    restored.close()
                }
            } finally {
                deleteTestDatabase(target)
            }
        }
}
