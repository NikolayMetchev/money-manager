package com.moneymanager.di.database

import com.moneymanager.database.InMemoryMoneyManagerDatabaseFactory
import com.moneymanager.database.JvmSqlDriverFactory
import com.moneymanager.database.MoneyManagerDatabaseFactory
import com.moneymanager.di.TestScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

@ContributesTo(TestScope::class)
actual interface TestDatabaseModule {
    @Provides
    @SingleIn(TestScope::class)
    fun provideMoneyManagerDatabaseFactory(): MoneyManagerDatabaseFactory = InMemoryMoneyManagerDatabaseFactory(JvmSqlDriverFactory)
}
