package com.moneymanager.database

import com.moneymanager.database.repository.AccountRepositoryImpl
import com.moneymanager.database.repository.AuditRepositoryImpl
import com.moneymanager.database.repository.CategoryRepositoryImpl
import com.moneymanager.database.repository.CsvImportRepositoryImpl
import com.moneymanager.database.repository.CurrencyRepositoryImpl
import com.moneymanager.database.repository.TransactionRepositoryImpl
import com.moneymanager.database.sql.MoneyManagerDatabase
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.AuditRepository
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.domain.repository.CsvImportRepository
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.domain.repository.TransactionRepository

/**
 * Container for all application repositories.
 * Instances are tied to a specific database and should be recreated when switching databases.
 */
class RepositorySet(
    database: MoneyManagerDatabaseWrapper,
) {
    val accountRepository: AccountRepository = AccountRepositoryImpl(database)
    val auditRepository: AuditRepository = AuditRepositoryImpl(database)
    val categoryRepository: CategoryRepository = CategoryRepositoryImpl(database)
    val csvImportRepository: CsvImportRepository = CsvImportRepositoryImpl(database)
    val currencyRepository: CurrencyRepository = CurrencyRepositoryImpl(database)
    val maintenanceService: DatabaseMaintenanceService = DatabaseMaintenanceServiceImpl(database)
    val transactionRepository: TransactionRepository = TransactionRepositoryImpl(database)
}
