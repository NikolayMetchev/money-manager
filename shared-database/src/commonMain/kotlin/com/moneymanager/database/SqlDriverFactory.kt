package com.moneymanager.database

import app.cash.sqldelight.db.SqlDriver

interface SqlDriverFactory {
    fun createSqlDriver(dbLocation: DbLocation): SqlDriver

    fun createInMemorySqlDriver(): SqlDriver
}
