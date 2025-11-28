package com.moneymanager.di.database

import com.moneymanager.database.AndroidDatabaseManager
import com.moneymanager.database.DatabaseManager
import com.moneymanager.di.TestScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * Android test module that provides a DatabaseManager for testing.
 * Requires Android Context from DbTestComponentParams.
 */
@ContributesTo(TestScope::class)
actual interface TestDatabaseModule {
    @Provides
    @SingleIn(TestScope::class)
    fun provideDatabaseManager(params: DbTestComponentParams): DatabaseManager {
        return AndroidDatabaseManager(params.context)
    }
}
