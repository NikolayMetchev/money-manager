package com.moneymanager.database.di

import com.moneymanager.database.DatabaseManager
import com.moneymanager.di.params.AppComponentParams
import com.moneymanager.di.scope.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * DI module that provides database-related dependencies.
 * Contributes to AppScope only.
 */
@ContributesTo(AppScope::class)
interface DatabaseManagerModule {
    /**
     * Provides the platform-specific DatabaseManager.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun provideDatabaseManager(params: AppComponentParams): DatabaseManager = createDatabaseManager(params)
}
