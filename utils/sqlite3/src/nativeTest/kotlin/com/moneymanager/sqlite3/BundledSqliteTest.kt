package com.moneymanager.sqlite3

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals

class BundledSqliteTest {
    @Test
    fun linksTheBundledSqliteWithTheFeaturesTheAppUses() {
        val driver = NativeSqliteDriver(EmptySchema, name = "bundled-sqlite-test.db", onConfiguration = { it.copy(inMemory = true) })
        try {
            assertEquals(EXPECTED_VERSION, driver.single("SELECT sqlite_version()") { it.getString(0) })
            // dbstat backs the Settings size breakdown.
            assertEquals(0L, driver.single("SELECT count(*) FROM dbstat WHERE name = 'missing'") { it.getLong(0) })
            assertEquals(8L, driver.single("SELECT CAST(pow(2, 3) AS INTEGER)") { it.getLong(0) })
        } finally {
            driver.close()
        }
    }

    private fun <T> SqlDriver.single(
        sql: String,
        read: (app.cash.sqldelight.db.SqlCursor) -> T?,
    ): T? =
        executeQuery(null, sql, { cursor ->
            cursor.next()
            QueryResult.Value(read(cursor))
        }, 0).value

    private object EmptySchema : SqlSchema<QueryResult.Value<Unit>> {
        override val version: Long = 1

        override fun create(driver: SqlDriver) = QueryResult.Unit

        override fun migrate(
            driver: SqlDriver,
            oldVersion: Long,
            newVersion: Long,
            vararg callbacks: app.cash.sqldelight.db.AfterVersion,
        ) = QueryResult.Unit
    }

    private companion object {
        const val EXPECTED_VERSION = "3.54.0"
    }
}
