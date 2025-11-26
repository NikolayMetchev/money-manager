package com.moneymanager.di.database

import android.content.Context
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
    fun provideSqlDriver(context: Context): SqlDriver {
        return FileDatabaseDriverFactory(context).createDriver()
    }
}

@ContributesTo(AppScope::class)
interface ContextModule {
    @Provides
    @SingleIn(AppScope::class)
    fun provideContext(params: AppComponentParams): Context = params.context
}
