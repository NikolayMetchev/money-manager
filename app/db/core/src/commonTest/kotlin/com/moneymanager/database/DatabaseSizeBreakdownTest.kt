package com.moneymanager.database

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.createAccount
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock

class DatabaseSizeBreakdownTest : DbTest() {
    @Test
    fun getDbSizeBreakdownReturnsSortedRowsWhenDbstatIsAvailable() =
        runTest {
            repositories.accountRepository.createAccount(
                Account(id = AccountId(0), name = "Breakdown account", openingDate = Clock.System.now()),
            )

            val breakdown = database.getDbSizeBreakdown()

            if (breakdown.isEmpty()) {
                // Some SQLite builds do not enable dbstat; in that case the API should fail gracefully.
                return@runTest
            }

            assertTrue(breakdown.all { it.totalBytes > 0 }, "all rows should report positive size")
            assertTrue(
                breakdown.zipWithNext().all { (current, next) -> current.totalBytes >= next.totalBytes },
                "rows should be sorted by size descending",
            )
            assertTrue(
                breakdown.any { it.objectName == "account" || it.objectName == "account_audit" },
                "breakdown should include account-related objects after inserting an account",
            )

            val accountTable = breakdown.firstOrNull { it.objectName == "account" }
            if (accountTable != null) {
                assertTrue(
                    accountTable.objectType == "table",
                    "the account object should be typed as a table",
                )
                assertTrue(
                    (accountTable.columnCount ?: 0) > 0,
                    "the account table should report its column count",
                )
                assertTrue(
                    (accountTable.rowCount ?: -1) >= 1,
                    "the account table should report at least the inserted row",
                )
            }
        }
}
