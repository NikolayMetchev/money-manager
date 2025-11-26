package com.moneymanager.database

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

/**
 * In-memory database driver factory for Android platform.
 * Useful for tests and temporary sessions.
 */
class InMemoryDatabaseDriverFactory(private val context: Context) : DatabaseDriverFactory {
    override fun createDriver(): SqlDriver {
        return AndroidSqliteDriver(
            schema = MoneyManagerDatabase.Schema,
            context = context,
            // null name creates in-memory database
            name = null,
        )
    }
}

/**
 * File-based database driver factory for Android platform.
 * Persists data to a SQLite database file managed by Android.
 *
 * @param context Android application context
 * @param databaseName Optional database name. If null, uses the default name.
 */
class FileDatabaseDriverFactory(
    private val context: Context,
    private val databaseName: String? = null,
) : DatabaseDriverFactory {
    override fun createDriver(): SqlDriver {
        return AndroidSqliteDriver(
            schema = MoneyManagerDatabase.Schema,
            context = context,
            name = databaseName ?: DEFAULT_DATABASE_NAME,
        )
    }

    companion object {
        private const val DEFAULT_DATABASE_NAME = "money_manager.db"
    }
}
