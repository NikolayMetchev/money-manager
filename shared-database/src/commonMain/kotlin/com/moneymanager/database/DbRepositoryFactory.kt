package com.moneymanager.database

import com.moneymanager.database.repository.AccountRepositoryImpl
import com.moneymanager.database.repository.CategoryRepositoryImpl
import com.moneymanager.database.repository.TransactionRepositoryImpl

/**
 * Simple factory that creates repository instances from a database.
 * No caching or lifecycle management - repositories are created on demand.
 */
class DbRepositoryFactory : RepositoryFactory {
    override fun createRepositories(database: MoneyManagerDatabase): RepositorySet {
        return RepositorySet(
            accountRepository = AccountRepositoryImpl(database),
            categoryRepository = CategoryRepositoryImpl(database),
            transactionRepository = TransactionRepositoryImpl(database),
        )
    }
}
