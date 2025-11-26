package com.moneymanager.di.database

import app.cash.sqldelight.db.SqlDriver
import com.moneymanager.database.InMemoryDatabaseDriverFactory
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

@ContributesTo(TestScope::class)
actual interface TestDatabaseModule {
    @Provides
    @SingleIn(TestScope::class)
    fun provideSqlDriver(): SqlDriver = InMemoryDatabaseDriverFactory().createDriver()
}
