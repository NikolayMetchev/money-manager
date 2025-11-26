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
    fun provideDatabaseDriverFactory(): DatabaseDriverFactory {
        return DatabaseDriverFactory()
    }

    @Provides
    @SingleIn(AppScope::class)
    fun provideSqlDriver(
        databaseDriverFactory: DatabaseDriverFactory,
        params: AppComponentParams,
    ): SqlDriver {
        // Use the path from params, or default if null
        val databasePath = params.databasePath ?: DatabaseDriverFactory.getDefaultDatabasePath()
        return databaseDriverFactory.createDriver(databasePath)
    }
}
