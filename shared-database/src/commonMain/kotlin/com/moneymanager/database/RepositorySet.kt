package com.moneymanager.database

import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.domain.repository.TransactionRepository

/**
 * Container for all application repositories.
 * Instances are tied to a specific database and should be recreated when switching databases.
 */
data class RepositorySet(
    val accountRepository: AccountRepository,
    val categoryRepository: CategoryRepository,
    val transactionRepository: TransactionRepository,
)
