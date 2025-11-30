package com.moneymanager.database

import com.moneymanager.database.repository.AccountRepositoryImpl
import com.moneymanager.database.repository.AssetRepositoryImpl
import com.moneymanager.database.repository.CategoryRepositoryImpl
import com.moneymanager.database.repository.TransactionRepositoryImpl
import com.moneymanager.database.sql.MoneyManagerDatabase
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.AssetRepository
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.domain.repository.TransactionRepository

/**
 * Container for all application repositories.
 * Instances are tied to a specific database and should be recreated when switching databases.
 */
class RepositorySet(database: MoneyManagerDatabase) {
    val assetRepository: AssetRepository = AssetRepositoryImpl(database)
    val accountRepository: AccountRepository = AccountRepositoryImpl(database)
    val categoryRepository: CategoryRepository = CategoryRepositoryImpl(database)
    val transactionRepository: TransactionRepository = TransactionRepositoryImpl(database)
}
