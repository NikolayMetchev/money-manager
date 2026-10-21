package com.moneymanager.test.database

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import com.moneymanager.database.SqliteException
import com.moneymanager.database.WebDatabaseManager
import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The browser's driver and manager over the worker's own handler (in memory, in this process). The shared DB suite
 * (app/db/core) runs here once the modules its tests link reach the browser; these pin down what only the browser
 * does: values crossing the message boundary, transactions taking turns on the one connection, and files in the
 * worker's store.
 */
class WebDatabaseManagerTest {
    private val manager = createTestDatabaseManager()

    private fun <T> withDatabase(block: suspend (MoneyManagerDatabaseWrapper) -> T) =
        runTest {
            val location = createTestDatabaseLocation()
            val database = manager.openDatabase(location)
            try {
                block(database)
            } finally {
                database.close()
                deleteTestDatabase(location)
            }
        }

    @Test
    fun `a 64-bit integer crosses the worker exactly`() =
        withDatabase { database ->
            // 1e18-scale amounts pass 2^53, where a JavaScript number starts rounding.
            for (value in listOf(Long.MAX_VALUE, Long.MIN_VALUE, 9_007_199_254_740_993L, 1_000_000_000_000_000_001L)) {
                val read = database.rawQuery("SELECT ?", 1, { bindLong(0, value) }) { it.getLong(0)!! }.awaitAsOne()
                assertEquals(value, read)
            }
        }

    @Test
    fun `text, reals, bytes and nulls cross the worker`() =
        withDatabase { database ->
            val bytes = byteArrayOf(0, -1, 127, -128, 42)
            val row =
                database
                    .rawQuery("SELECT ?, ?, ?, ?", 4, {
                        bindString(0, "ünïcødé ✓")
                        bindDouble(1, 0.1)
                        bindBytes(2, bytes)
                        bindString(3, null)
                    }) { listOf(it.getString(0), it.getDouble(1), it.getBytes(2), it.getString(3)) }
                    .awaitAsOne()
            assertEquals("ünïcødé ✓", row[0])
            assertEquals(0.1, row[1])
            assertContentEquals(bytes, row[2] as ByteArray)
            assertNull(row[3])
        }

    @Test
    fun `a new database is created with its schema and currencies`() =
        withDatabase { database ->
            val codes = database.rawQuery("SELECT code FROM currency ORDER BY code") { it.getString(0)!! }.awaitAsList()
            assertTrue("USD" in codes && "EUR" in codes, "currencies: $codes")
        }

    @Test
    fun `a failing statement reports SQLite's error`() =
        withDatabase { database ->
            val failure = assertFailsWith<SqliteException> { database.execute(null, "INSERT INTO no_such_table VALUES (1)", 0) }
            assertTrue("no_such_table" in failure.message.orEmpty(), failure.message)
        }

    @Test
    fun `a rolled back transaction leaves nothing behind`() =
        withDatabase { database ->
            val before = assetCount(database)
            assertFailsWith<IllegalStateException> {
                database.transaction {
                    database.execute(null, "INSERT INTO asset DEFAULT VALUES", 0)
                    database.transaction { error("fails") }
                }
            }
            assertEquals(before, assetCount(database))
        }

    @Test
    fun `transactions from two coroutines take turns`() =
        withDatabase { database ->
            val before = assetCount(database)
            // Both would BEGIN on the one connection if they ran together, and the second BEGIN would fail.
            coroutineScope {
                repeat(2) {
                    launch {
                        database.transaction {
                            database.execute(null, "INSERT INTO asset DEFAULT VALUES", 0)
                            database.execute(null, "INSERT INTO asset DEFAULT VALUES", 0)
                        }
                    }
                }
            }
            assertEquals(before + 4, assetCount(database))
        }

    @Test
    fun `a closed database reopens with its data`() =
        runTest {
            val location = createTestDatabaseLocation()
            try {
                val created = manager.openDatabase(location)
                created.execute(null, "INSERT INTO asset DEFAULT VALUES", 0)
                val count = assetCount(created)
                created.close()

                assertTrue(manager.databaseExists(location))
                val reopened = manager.openDatabase(location)
                assertEquals(count, assetCount(reopened))
                reopened.close()
            } finally {
                deleteTestDatabase(location)
            }
        }

    @Test
    fun `a snapshot restores as a database of its own`() =
        runTest {
            val source = createTestDatabaseLocation()
            val copy = createTestDatabaseLocation()
            try {
                val database = manager.openDatabase(source)
                database.execute(null, "INSERT INTO asset DEFAULT VALUES", 0)
                val count = assetCount(database)
                val bytes = manager.snapshot(database)
                database.close()

                manager.restore(copy, bytes)
                assertEquals(bytes.size.toLong(), manager.databaseSizeBytes(copy))
                val restored = manager.openDatabase(copy)
                assertEquals(count, assetCount(restored))
                restored.close()
            } finally {
                deleteTestDatabase(source)
                deleteTestDatabase(copy)
            }
        }

    @Test
    fun `a failed creation deletes the database`() =
        runTest {
            val location = createTestDatabaseLocation()
            val failing = WebDatabaseManager(MemorySqliteChannel, currenciesToSeed = { error("no currencies") })
            assertFailsWith<IllegalStateException> { failing.openDatabase(location) }
            assertFalse(manager.databaseExists(location))
        }

    private suspend fun assetCount(database: MoneyManagerDatabaseWrapper): Long =
        database.rawQuery("SELECT COUNT(*) FROM asset") { it.getLong(0)!! }.awaitAsOne()
}
