package com.moneymanager.database

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.moneymanager.test.database.createTestDatabaseLocation
import com.moneymanager.test.database.deleteTestDatabase
import java.io.File
import java.sql.DriverManager
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The driver pools its connections: reads reuse one rather than reopening the file and parsing the schema. */
class CachingJdbcSqliteDriverTest {
    private val location = createTestDatabaseLocation()
    private val properties = Properties().apply { put("busy_timeout", "4321") }
    private val driver = CachingJdbcSqliteDriver("jdbc:sqlite:${location.path}", properties)

    @AfterTest
    fun tearDown() {
        driver.close()
        deleteTestDatabase(location)
    }

    @Test
    fun `a query outside a transaction reuses the previous query's connection`() {
        // A TEMP table lives on one connection only, so reading it back proves the connection was kept.
        driver.execute(null, "CREATE TEMP TABLE marker (value INTEGER)", 0)
        driver.execute(null, "INSERT INTO marker VALUES (7)", 0)

        assertEquals(listOf(7L), driver.longs("SELECT value FROM marker"))
    }

    @Test
    fun `every pooled connection gets the connection properties`() {
        // Each read blocks inside its mapper, holding its connection, so the pool has to open one per thread.
        val threads = 4
        val holding = CountDownLatch(threads)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val timeouts =
                (1..threads).map {
                    pool.submit<Long> {
                        driver
                            .executeQuery(
                                null,
                                "PRAGMA busy_timeout",
                                { cursor ->
                                    holding.countDown()
                                    holding.await(10, TimeUnit.SECONDS)
                                    cursor.next()
                                    QueryResult.Value(cursor.getLong(0)!!)
                                },
                                0,
                            ).value
                    }
                }
            assertEquals(List(threads) { 4321L }, timeouts.map { it.get(10, TimeUnit.SECONDS) })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `an idle pooled connection sees what another connection committed`() {
        driver.execute(null, "PRAGMA journal_mode = WAL", 0)
        driver.execute(null, "CREATE TABLE item (value INTEGER)", 0)
        // Run a cached statement, then leave its connection idle in the pool.
        assertEquals(listOf(0L), driver.longs("SELECT count(*) FROM item", identifier = 1))

        DriverManager.getConnection("jdbc:sqlite:${location.path}").use { other ->
            other.createStatement().use { it.executeUpdate("INSERT INTO item VALUES (1)") }
        }

        assertEquals(listOf(1L), driver.longs("SELECT count(*) FROM item", identifier = 1))
    }

    @Test
    fun `a write leaves its pooled connection free to VACUUM`() {
        driver.execute(null, "CREATE TABLE item (value INTEGER)", 0)
        driver.execute(1, "INSERT INTO item VALUES (1)", 0)
        val snapshot = File.createTempFile("vacuum", ".db").apply { delete() }
        try {
            driver.execute(null, "VACUUM INTO '$snapshot'", 0)
            assertTrue(snapshot.length() > 0)
        } finally {
            snapshot.delete()
        }
    }

    @Test
    fun `close closes the pooled connections`() {
        driver.execute(null, "CREATE TEMP TABLE marker (value INTEGER)", 0)
        driver.close()

        // The next query opens a fresh connection, which never had the TEMP table.
        assertFailsWith<Exception> { driver.longs("SELECT value FROM marker") }
    }
}

private fun SqlDriver.longs(
    sql: String,
    identifier: Int? = null,
): List<Long> =
    executeQuery(
        identifier,
        sql,
        { cursor ->
            QueryResult.Value(
                buildList {
                    while (cursor.next().value) add(cursor.getLong(0)!!)
                },
            )
        },
        0,
    ).value
