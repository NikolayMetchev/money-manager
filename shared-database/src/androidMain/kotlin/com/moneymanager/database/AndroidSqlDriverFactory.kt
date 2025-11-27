package com.moneymanager.database

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.moneymanager.database.DbLocation

class AndroidSqlDriverFactory(private val context: Context): SqlDriverFactory {
    override fun createSqlDriver(dbLocation: DbLocation): SqlDriver {
        return AndroidSqliteDriver(
            schema = MoneyManagerDatabase.Schema,
            context = context,
            name = dbLocation.name,
        )
    }

    override fun createInMemorySqlDriver(): SqlDriver {
        return AndroidSqliteDriver(
            schema = MoneyManagerDatabase.Schema,
            context = context,
            // null name creates in-memory database
            name = null,
        )
    }
}