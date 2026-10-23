package com.moneymanager.database

import app.cash.sqldelight.async.coroutines.awaitAsOne
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The queries are generated async, so a transaction's body suspends. The JVM and native drivers keep the open
 * transaction per thread, so these pin down that a nested transaction joins its enclosing one.
 */
class SuspendingTransactionTest : DbTest() {
    private suspend fun assetCount(): Long = database.rawQuery("SELECT COUNT(*) FROM asset") { it.getLong(0)!! }.awaitAsOne()

    @Test
    fun `a nested transaction sees its enclosing transaction's writes`() =
        runTest {
            val before = assetCount()

            database.transaction {
                database.execute(null, "INSERT INTO asset DEFAULT VALUES", 0)
                database.transaction {
                    database.execute(null, "INSERT INTO asset DEFAULT VALUES", 0)
                    assertEquals(before + 2, assetCount())
                }
            }

            assertEquals(before + 2, assetCount())
        }

    @Test
    fun `a failure in a nested transaction rolls back the enclosing one`() =
        runTest {
            val before = assetCount()

            assertFailsWith<IllegalStateException> {
                database.transaction {
                    database.execute(null, "INSERT INTO asset DEFAULT VALUES", 0)
                    database.transaction { error("fails") }
                }
            }

            assertEquals(before, assetCount())
        }
}
