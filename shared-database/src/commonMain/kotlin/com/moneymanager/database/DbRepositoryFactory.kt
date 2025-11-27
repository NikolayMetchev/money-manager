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
    private var cachedDatabase: MoneyManagerDatabase? = null

    private fun getDatabase(listener: DefaultLocationMissingListener): MoneyManagerDatabase {
        return cachedDatabase ?: moneyManagerFactory.createMoneyManager(listener).also { cachedDatabase = it }
    }

    override fun createAccountRepository(listener: DefaultLocationMissingListener): AccountRepository {
        return AccountRepositoryImpl(getDatabase(listener))
    }

    override fun createCategoryRepository(listener: DefaultLocationMissingListener): CategoryRepository {
        return CategoryRepositoryImpl(getDatabase(listener))
    }

    override fun createTransactionRepository(listener: DefaultLocationMissingListener): TransactionRepository {
        return TransactionRepositoryImpl(getDatabase(listener))
    }
}
