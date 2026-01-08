package com.moneymanager.di.database

import com.moneymanager.database.DatabaseMaintenanceService
import com.moneymanager.database.DatabaseMaintenanceServiceImpl
import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.database.repository.AccountRepositoryImpl
import com.moneymanager.database.repository.AttributeTypeRepositoryImpl
import com.moneymanager.database.repository.AuditRepositoryImpl
import com.moneymanager.database.repository.CategoryRepositoryImpl
import com.moneymanager.database.repository.CsvAccountMappingRepositoryImpl
import com.moneymanager.database.repository.CsvImportRepositoryImpl
import com.moneymanager.database.repository.CsvImportStrategyRepositoryImpl
import com.moneymanager.database.repository.CurrencyRepositoryImpl
import com.moneymanager.database.repository.DeviceRepositoryImpl
import com.moneymanager.database.repository.TransactionRepositoryImpl
import com.moneymanager.database.repository.TransferAttributeRepositoryImpl
import com.moneymanager.database.repository.TransferSourceRepositoryImpl
import com.moneymanager.database.service.CsvStrategyExportService
import com.moneymanager.database.sql.TransferSourceQueries
import com.moneymanager.di.DatabaseScope
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.AttributeTypeRepository
import com.moneymanager.domain.repository.AuditRepository
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.domain.repository.CsvAccountMappingRepository
import com.moneymanager.domain.repository.CsvImportRepository
import com.moneymanager.domain.repository.CsvImportStrategyRepository
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.domain.repository.DeviceRepository
import com.moneymanager.domain.repository.TransactionRepository
import com.moneymanager.domain.repository.TransferAttributeRepository
import com.moneymanager.domain.repository.TransferSourceRepository
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * Module that provides all repository implementations.
 */
@ContributesTo(DatabaseScope::class)
interface RepositoryModule {
    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideTransferSourceQueries(database: MoneyManagerDatabaseWrapper): TransferSourceQueries = database.transferSourceQueries

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideAccountRepository(database: MoneyManagerDatabaseWrapper): AccountRepository = AccountRepositoryImpl(database)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideAttributeTypeRepository(database: MoneyManagerDatabaseWrapper): AttributeTypeRepository =
        AttributeTypeRepositoryImpl(database)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideAuditRepository(database: MoneyManagerDatabaseWrapper): AuditRepository = AuditRepositoryImpl(database)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideCategoryRepository(database: MoneyManagerDatabaseWrapper): CategoryRepository = CategoryRepositoryImpl(database)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideDeviceRepository(database: MoneyManagerDatabaseWrapper): DeviceRepository = DeviceRepositoryImpl(database)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideCsvImportRepository(
        database: MoneyManagerDatabaseWrapper,
        deviceId: DeviceId,
    ): CsvImportRepository = CsvImportRepositoryImpl(database, deviceId)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideCsvAccountMappingRepository(database: MoneyManagerDatabaseWrapper): CsvAccountMappingRepository =
        CsvAccountMappingRepositoryImpl(database)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideCsvImportStrategyRepository(database: MoneyManagerDatabaseWrapper): CsvImportStrategyRepository =
        CsvImportStrategyRepositoryImpl(database)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideCurrencyRepository(database: MoneyManagerDatabaseWrapper): CurrencyRepository = CurrencyRepositoryImpl(database)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideMaintenanceService(database: MoneyManagerDatabaseWrapper): DatabaseMaintenanceService =
        DatabaseMaintenanceServiceImpl(database)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideCsvStrategyExportService(
        accountRepository: AccountRepository,
        currencyRepository: CurrencyRepository,
        categoryRepository: CategoryRepository,
    ): CsvStrategyExportService = CsvStrategyExportService(accountRepository, currencyRepository, categoryRepository)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideTransactionRepository(database: MoneyManagerDatabaseWrapper): TransactionRepository = TransactionRepositoryImpl(database)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideTransferAttributeRepository(database: MoneyManagerDatabaseWrapper): TransferAttributeRepository =
        TransferAttributeRepositoryImpl(database)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideTransferSourceRepository(
        database: MoneyManagerDatabaseWrapper,
        deviceRepository: DeviceRepository,
    ): TransferSourceRepository = TransferSourceRepositoryImpl(database, deviceRepository)
}
