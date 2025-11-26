package com.moneymanager.di.database

import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.domain.repository.TransactionRepository
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides

@DependencyGraph(TestScope::class)
interface DbTestComponent {
    val accountRepository: AccountRepository
    val categoryRepository: CategoryRepository
    val transactionRepository: TransactionRepository

    @DependencyGraph.Factory
    interface Factory {
        fun create(
            @Provides params: DbTestComponentParams,
        ): DbTestComponent
    }
}
