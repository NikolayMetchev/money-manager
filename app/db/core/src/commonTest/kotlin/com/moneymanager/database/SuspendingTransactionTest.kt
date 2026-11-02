package com.moneymanager.database

import app.cash.sqldelight.async.coroutines.awaitAsOne
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.read.testReads
import com.moneymanager.test.database.write.testWrites
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The queries are generated async, so a transaction's body suspends. The JVM and native drivers keep the open
 * transaction per thread, so these pin down that a nested transaction joins its enclosing one.
 */
class SuspendingTransactionTest : DbTest() {
    private suspend fun assetCount(): Long =
        database.testReads.rowCountSelectQueries
            .countAssets()
            .awaitAsOne()

    @Test
    fun `a nested transaction sees its enclosing transaction's writes`() =
        runDbTest {
            val before = assetCount()

            database.transaction {
                database.testWrites.fixtureWriteQueries.insertEmptyAsset()
                database.transaction {
                    database.testWrites.fixtureWriteQueries.insertEmptyAsset()
                    assertEquals(before + 2, assetCount())
                }
            }

            assertEquals(before + 2, assetCount())
        }

    @Test
    fun `a failure in a nested transaction rolls back the enclosing one`() =
        runDbTest {
            val before = assetCount()

            assertFailsWith<IllegalStateException> {
                database.transaction {
                    database.testWrites.fixtureWriteQueries.insertEmptyAsset()
                    database.transaction { error("fails") }
                }
            }

            assertEquals(before, assetCount())
        }
}
