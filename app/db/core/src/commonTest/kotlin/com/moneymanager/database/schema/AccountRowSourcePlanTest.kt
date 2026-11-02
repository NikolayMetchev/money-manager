package com.moneymanager.database.schema

import app.cash.sqldelight.async.coroutines.awaitAsList
import com.moneymanager.test.database.DbTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the query plan of the transactions list's page queries over `account_row_source`. Opening a transaction
 * deep in a large account (an OFFSET page 14,000 rows in) took over a minute when the plan sorted every row and
 * computed `is_reconciled` by walking all reconciled relationships per row. Databases carry no `sqlite_stat1`, so
 * the plan here is the plan a real database gets.
 */
class AccountRowSourcePlanTest : DbTest() {
    private val offsetPageSql =
        """
        SELECT * FROM account_row_source
        WHERE account_id = ?
          AND (? IS NULL OR asset_id = ?)
        ORDER BY timestamp DESC, id DESC
        LIMIT ? OFFSET ?
        """.trimIndent()

    @Test
    fun `offset page needs no sort`() =
        runDbTest {
            val plan = queryPlan()
            assertFalse(plan.any { "TEMP B-TREE" in it }, "Expected no sort step:\n${plan.joinToString("\n")}")
        }

    @Test
    fun `is_reconciled looks members up by transaction`() =
        runDbTest {
            val plan = queryPlan()
            assertFalse(
                plan.any { "idx_relationship_type" in it },
                "is_reconciled must not walk relationships by type:\n${plan.joinToString("\n")}",
            )
            // The fee and pass-through EXISTS subqueries also alias relationship as r, so pin is_reconciled's pair:
            // its member lookup by transaction, then the relationship by primary key.
            val memberLookup =
                plan.indexOfFirst { it.startsWith("SEARCH rm USING INDEX idx_relationship_member_transaction ") }
            assertTrue(
                memberLookup >= 0 && plan.getOrNull(memberLookup + 1)?.startsWith("SEARCH r USING INTEGER PRIMARY KEY") == true,
                "is_reconciled must look members up by transaction, then relationship by id:\n${plan.joinToString("\n")}",
            )
        }

    private suspend fun queryPlan(): List<String> =
        database
            .rawQuery("EXPLAIN QUERY PLAN $offsetPageSql", parameters = 5, binders = {
                bindLong(0, 1L)
                bindLong(1, null)
                bindLong(2, null)
                bindLong(3, 30L)
                bindLong(4, 100L)
            }) { cursor -> cursor.getString(3)!! }
            .awaitAsList()
}
