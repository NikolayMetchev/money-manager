package com.moneymanager.di.database

import app.cash.sqldelight.db.SqlDriver
import com.moneymanager.database.FileDatabaseDriverFactory
import com.moneymanager.domain.di.AppComponentParams
import com.moneymanager.domain.di.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

@ContributesTo(AppScope::class)
actual interface DatabaseDriverFactoryModule {
    @Provides
    @SingleIn(AppScope::class)
    fun provideSqlDriver(params: AppComponentParams): SqlDriver {
        return FileDatabaseDriverFactory(params.context).createDriver()
    }
}
