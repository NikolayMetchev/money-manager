package com.moneymanager.di

import com.moneymanager.data.DatabaseDriverFactory
import com.moneymanager.database.MoneyManagerDatabase
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.domain.repository.TransactionRepository
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

@DependencyGraph(AppScope::class)
abstract class AppComponent {
    abstract val accountRepository: AccountRepository
    abstract val categoryRepository: CategoryRepository
    abstract val transactionRepository: TransactionRepository

    @Provides
    @SingleIn(AppScope::class)
    fun provideDatabase(driverFactory: DatabaseDriverFactory): MoneyManagerDatabase {
        return MoneyManagerDatabase(driverFactory.createDriver())
    }

    @DependencyGraph.Factory
    interface Factory {
        fun create(@Provides driverFactory: DatabaseDriverFactory): AppComponent
    }
}
