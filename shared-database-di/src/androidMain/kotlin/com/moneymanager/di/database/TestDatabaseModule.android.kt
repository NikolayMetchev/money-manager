package com.moneymanager.di.database

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import com.moneymanager.database.InMemoryDatabaseDriverFactory
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

@ContributesTo(TestScope::class)
actual interface TestDatabaseModule {
    @Provides
    @SingleIn(TestScope::class)
    fun provideSqlDriver(context: Context): SqlDriver = InMemoryDatabaseDriverFactory(context).createDriver()
}
