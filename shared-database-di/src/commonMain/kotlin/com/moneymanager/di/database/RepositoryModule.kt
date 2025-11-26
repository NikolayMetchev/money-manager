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

@ContributesTo(AppScope::class)
@ContributesTo(TestScope::class)
interface RepositoryModule {
    @Provides
    fun provideAccountRepository(database: MoneyManagerDatabase): AccountRepository {
        return AccountRepositoryImpl(database)
    }

    @Provides
    fun provideCategoryRepository(database: MoneyManagerDatabase): CategoryRepository {
        return CategoryRepositoryImpl(database)
    }

    @Provides
    fun provideTransactionRepository(database: MoneyManagerDatabase): TransactionRepository {
        return TransactionRepositoryImpl(database)
    }
}
