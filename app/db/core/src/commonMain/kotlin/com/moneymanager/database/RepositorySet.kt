package com.moneymanager.database

import com.moneymanager.database.repository.AccountRepositoryImpl
import com.moneymanager.database.repository.AttributeTypeRepositoryImpl
import com.moneymanager.database.repository.AuditRepositoryImpl
import com.moneymanager.database.repository.CategoryRepositoryImpl
import com.moneymanager.database.repository.CsvImportRepositoryImpl
import com.moneymanager.database.repository.CsvImportStrategyRepositoryImpl
import com.moneymanager.database.repository.CurrencyRepositoryImpl
import com.moneymanager.database.repository.DeviceRepositoryImpl
import com.moneymanager.database.repository.TransactionIdRepositoryImpl
import com.moneymanager.database.repository.TransactionRepositoryImpl
import com.moneymanager.database.repository.TransferAttributeAuditRepositoryImpl
import com.moneymanager.database.repository.TransferAttributeRepositoryImpl
import com.moneymanager.database.repository.TransferSourceRepositoryImpl
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.AttributeTypeRepository
import com.moneymanager.domain.repository.AuditRepository
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.domain.repository.CsvImportRepository
import com.moneymanager.domain.repository.CsvImportStrategyRepository
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.domain.repository.DeviceRepository
import com.moneymanager.domain.repository.TransactionIdRepository
import com.moneymanager.domain.repository.TransactionRepository
import com.moneymanager.domain.repository.TransferAttributeAuditRepository
import com.moneymanager.domain.repository.TransferAttributeRepository
import com.moneymanager.domain.repository.TransferSourceRepository

/**
 * Container for all application repositories.
 * Instances are tied to a specific database and should be recreated when switching databases.
 */
class RepositorySet(
    database: MoneyManagerDatabaseWrapper,
) {
    val accountRepository: AccountRepository = AccountRepositoryImpl(database)
    val attributeTypeRepository: AttributeTypeRepository = AttributeTypeRepositoryImpl(database)
    val auditRepository: AuditRepository = AuditRepositoryImpl(database)
    val categoryRepository: CategoryRepository = CategoryRepositoryImpl(database)
    val deviceRepository: DeviceRepository = DeviceRepositoryImpl(database)
    val csvImportRepository: CsvImportRepository = CsvImportRepositoryImpl(database, deviceRepository)
    val csvImportStrategyRepository: CsvImportStrategyRepository = CsvImportStrategyRepositoryImpl(database)
    val currencyRepository: CurrencyRepository = CurrencyRepositoryImpl(database)
    val maintenanceService: DatabaseMaintenanceService = DatabaseMaintenanceServiceImpl(database)
    val transactionIdRepository: TransactionIdRepository = TransactionIdRepositoryImpl(database)
    val transactionRepository: TransactionRepository = TransactionRepositoryImpl(database, deviceRepository)
    val transferAttributeRepository: TransferAttributeRepository = TransferAttributeRepositoryImpl(database)
    val transferAttributeAuditRepository: TransferAttributeAuditRepository =
        TransferAttributeAuditRepositoryImpl(database)
    val transferSourceRepository: TransferSourceRepository = TransferSourceRepositoryImpl(database, deviceRepository)
}
