package com.moneymanager.database.schema

import app.cash.sqldelight.async.coroutines.awaitAsList
import com.moneymanager.database.rawsql.RawSql
import com.moneymanager.test.database.DbTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the plan of `selectLegsForAccounts`' `match_ids` subquery (ReconciliationSelect.sq, which this mirrors:
 * a query's SQL isn't readable at runtime). Databases carry no `sqlite_stat1`, so SQLite drove it from
 * `relationship`'s type index, walking every reconciliation-match for each leg: minutes on a database with
 * ~15k matches. The plan here is the plan a real database gets.
 */
class ReconciliationLegsPlanTest : DbTest() {
    private val matchIdsSql =
        """
        SELECT tr.id, COALESCE((SELECT GROUP_CONCAT(rm.relationship_id) FROM relationship_member rm
         CROSS JOIN relationship r ON r.id = rm.relationship_id
         WHERE rm.transaction_id = tr.id AND r.relationship_type_id = 6), '') AS match_ids
        FROM transfer tr
        WHERE tr.target_account_id IN (1, 2)
        """.trimIndent()

    @Test
    fun `match ids look members up by transaction`() =
        runDbTest {
            val plan =
                RawSql(database.driver)
                    .query("EXPLAIN QUERY PLAN $matchIdsSql") { cursor -> cursor.getString(3)!! }
                    .awaitAsList()
            assertFalse(
                plan.any { "idx_relationship_type" in it },
                "match_ids must not walk relationships by type:\n${plan.joinToString("\n")}",
            )
            val memberLookup =
                plan.indexOfFirst { it.startsWith("SEARCH rm USING INDEX idx_relationship_member_transaction ") }
            assertTrue(
                memberLookup >= 0 && plan.getOrNull(memberLookup + 1)?.startsWith("SEARCH r USING INTEGER PRIMARY KEY") == true,
                "match_ids must look members up by transaction, then relationship by id:\n${plan.joinToString("\n")}",
            )
        }
}
