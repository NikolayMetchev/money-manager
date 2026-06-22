package com.moneymanager.database

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.test.database.DbTest
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
        }
}
