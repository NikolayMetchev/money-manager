package com.moneymanager.di.database

import app.cash.sqldelight.db.SqlDriver
import com.moneymanager.database.MoneyManagerDatabase
import com.moneymanager.domain.di.AppScope
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
