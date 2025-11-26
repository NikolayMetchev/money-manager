package com.moneymanager.di.database

import app.cash.sqldelight.db.SqlDriver
import com.moneymanager.database.DatabaseDriverFactory
import com.moneymanager.domain.di.AppComponentParams
import com.moneymanager.domain.di.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

@ContributesTo(AppScope::class)
actual interface DatabaseDriverFactoryModule {
    @Provides
    @SingleIn(AppScope::class)
    fun provideDatabaseDriverFactory(params: AppComponentParams): DatabaseDriverFactory {
        return DatabaseDriverFactory(params.context)
    }

    @Provides
    @SingleIn(AppScope::class)
    fun provideSqlDriver(databaseDriverFactory: DatabaseDriverFactory): SqlDriver {
        // On Android, path is ignored and system-managed location is used
        return databaseDriverFactory.createDriver(databasePath = null)
    }
}
