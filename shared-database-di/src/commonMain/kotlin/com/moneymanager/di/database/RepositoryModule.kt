package com.moneymanager.di.database

import com.moneymanager.database.MoneyManagerDatabase
import com.moneymanager.database.repository.AccountRepositoryImpl
import com.moneymanager.database.repository.CategoryRepositoryImpl
import com.moneymanager.database.repository.TransactionRepositoryImpl
import com.moneymanager.domain.di.AppScope
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.domain.repository.TransactionRepository
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

@ContributesTo(AppScope::class)
interface RepositoryModule {
    @Provides
    @SingleIn(AppScope::class)
    fun provideAccountRepository(database: MoneyManagerDatabase): AccountRepository {
        return AccountRepositoryImpl(database)
    }

    @Provides
    @SingleIn(AppScope::class)
    fun provideCategoryRepository(database: MoneyManagerDatabase): CategoryRepository {
        return CategoryRepositoryImpl(database)
    }

    @Provides
    @SingleIn(AppScope::class)
    fun provideTransactionRepository(database: MoneyManagerDatabase): TransactionRepository {
        return TransactionRepositoryImpl(database)
    }
}
