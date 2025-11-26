package com.moneymanager.di.database

import app.cash.sqldelight.db.SqlDriver
import com.moneymanager.database.MoneyManagerDatabase
import com.moneymanager.domain.di.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides

@ContributesTo(AppScope::class)
@ContributesTo(TestScope::class)
interface DatabaseModule {
    @Provides
    fun provideDatabase(driver: SqlDriver): MoneyManagerDatabase {
        return MoneyManagerDatabase(driver)
    }
}
