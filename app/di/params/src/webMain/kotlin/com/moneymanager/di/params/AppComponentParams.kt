package com.moneymanager.di.params

import com.moneymanager.database.SqliteChannel

actual class AppComponentParams(
    /** The way to the database worker, which the browser app starts once (`WorkerSqliteChannel`). */
    val sqliteChannel: SqliteChannel,
)
