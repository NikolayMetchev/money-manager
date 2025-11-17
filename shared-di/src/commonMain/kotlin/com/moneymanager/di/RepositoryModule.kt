package com.moneymanager.di

import com.moneymanager.data.repository.AccountRepositoryImpl
import com.moneymanager.data.repository.CategoryRepositoryImpl
import com.moneymanager.data.repository.TransactionRepositoryImpl
import com.moneymanager.database.MoneyManagerDatabase
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