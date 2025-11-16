package com.moneymanager.di

import com.moneymanager.data.DatabaseDriverFactory
import com.moneymanager.data.repository.AccountRepositoryImpl
import com.moneymanager.data.repository.CategoryRepositoryImpl
import com.moneymanager.data.repository.TransactionRepositoryImpl
import com.moneymanager.database.MoneyManagerDatabase
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.domain.repository.TransactionRepository

/**
 * Application DI component
 *
 * Note: Currently using manual DI. Metro annotations are in place for future migration
 * when the framework becomes more stable.
 */
interface AppComponent {
    val accountRepository: AccountRepository
    val categoryRepository: CategoryRepository
    val transactionRepository: TransactionRepository

    companion object {
        fun create(driverFactory: DatabaseDriverFactory): AppComponent {
            return AppComponentImpl(driverFactory)
        }
    }
}

private class AppComponentImpl(
    driverFactory: DatabaseDriverFactory
) : AppComponent {

    private val database: MoneyManagerDatabase by lazy {
        MoneyManagerDatabase(driverFactory.createDriver())
    }

    override val accountRepository: AccountRepository by lazy {
        AccountRepositoryImpl(database)
    }

    override val categoryRepository: CategoryRepository by lazy {
        CategoryRepositoryImpl(database)
    }

    override val transactionRepository: TransactionRepository by lazy {
        TransactionRepositoryImpl(database)
    }
}
