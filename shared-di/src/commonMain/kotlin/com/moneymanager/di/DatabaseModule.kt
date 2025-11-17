package com.moneymanager.di

import com.moneymanager.data.DatabaseDriverFactory
import com.moneymanager.database.MoneyManagerDatabase
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

@ContributesTo(AppScope::class)
interface DatabaseModule {
    @Provides
    @SingleIn(AppScope::class)
    fun provideDatabase(driverFactory: DatabaseDriverFactory): MoneyManagerDatabase {
        return MoneyManagerDatabase(driverFactory.createDriver())
    }
}