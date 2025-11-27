package com.moneymanager.database

import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.domain.repository.TransactionRepository

interface RepositoryFactory {
    fun createAccountRepository(listener: DefaultLocationMissingListener): AccountRepository

    fun createCategoryRepository(listener: DefaultLocationMissingListener): CategoryRepository

    fun createTransactionRepository(listener: DefaultLocationMissingListener): TransactionRepository
}
