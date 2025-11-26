package com.moneymanager.di

import com.moneymanager.domain.di.AppComponentParams
import com.moneymanager.domain.di.AppScope
import com.moneymanager.domain.model.AppVersion
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
    val appVersion: AppVersion

    @DependencyGraph.Factory
    interface Factory {
        fun create(
            @Provides params: AppComponentParams,
        ): AppComponent
    }
}
