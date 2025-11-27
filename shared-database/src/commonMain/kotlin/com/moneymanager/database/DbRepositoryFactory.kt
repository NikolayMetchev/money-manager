package com.moneymanager.database

import com.moneymanager.database.repository.AccountRepositoryImpl
import com.moneymanager.database.repository.CategoryRepositoryImpl
import com.moneymanager.database.repository.TransactionRepositoryImpl
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.domain.repository.TransactionRepository

class DbRepositoryFactory(
    private val moneyManagerFactory: MoneyManagerDatabaseFactory,
) : RepositoryFactory {
    override fun createAccountRepository(listener: DefaultLocationMissingListener): AccountRepository {
        return AccountRepositoryImpl(moneyManagerFactory.createMoneyManager(listener))
    }

    override fun createCategoryRepository(listener: DefaultLocationMissingListener): CategoryRepository {
        return CategoryRepositoryImpl(moneyManagerFactory.createMoneyManager(listener))
    }

    override fun createTransactionRepository(listener: DefaultLocationMissingListener): TransactionRepository {
        return TransactionRepositoryImpl(moneyManagerFactory.createMoneyManager(listener))
    }
}
