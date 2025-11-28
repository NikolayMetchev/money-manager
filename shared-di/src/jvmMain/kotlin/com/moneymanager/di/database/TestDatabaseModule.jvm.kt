package com.moneymanager.di.database

import com.moneymanager.database.DatabaseManager
import com.moneymanager.database.JvmDatabaseManager
import com.moneymanager.di.TestScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * JVM test module that provides a DatabaseManager for testing.
 * Tests use the same JvmDatabaseManager but can create in-memory databases.
 */
@ContributesTo(TestScope::class)
actual interface TestDatabaseModule {
    @Provides
    @SingleIn(TestScope::class)
    fun provideDatabaseManager(): DatabaseManager = JvmDatabaseManager()
}
