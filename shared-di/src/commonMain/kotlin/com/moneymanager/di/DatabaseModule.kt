package com.moneymanager.di

import app.cash.sqldelight.db.SqlDriver
import com.moneymanager.database.MoneyManagerDatabase
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

@ContributesTo(AppScope::class)
interface DatabaseModule {
    @Provides
    @SingleIn(AppScope::class)
    fun provideDatabase(driver: SqlDriver): MoneyManagerDatabase {
        return MoneyManagerDatabase(driver)
    }
}
