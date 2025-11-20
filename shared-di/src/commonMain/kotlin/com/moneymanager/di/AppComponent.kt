package com.moneymanager.di

import app.cash.sqldelight.db.SqlDriver
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.domain.repository.TransactionRepository
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides

@DependencyGraph(AppScope::class)
interface AppComponent {
    val accountRepository: AccountRepository
    val categoryRepository: CategoryRepository
    val transactionRepository: TransactionRepository

    @DependencyGraph.Factory
    interface Factory {
        fun create(@Provides driver: SqlDriver): AppComponent
    }
}