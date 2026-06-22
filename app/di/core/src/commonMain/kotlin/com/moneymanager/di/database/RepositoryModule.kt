package com.moneymanager.di.database

import com.moneymanager.database.DatabaseMaintenanceService
import com.moneymanager.database.DatabaseMaintenanceServiceImpl
import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.database.repository.AccountAttributeReadRepositoryImpl
import com.moneymanager.database.repository.AccountAttributeWriteRepositoryImpl
import com.moneymanager.database.repository.AccountReadRepositoryImpl
import com.moneymanager.database.repository.AccountWriteRepositoryImpl
import com.moneymanager.database.repository.ApiImportStrategyReadRepositoryImpl
import com.moneymanager.database.repository.ApiImportStrategyWriteRepositoryImpl
import com.moneymanager.database.repository.ApiSessionReadRepositoryImpl
import com.moneymanager.database.repository.ApiSessionWriteRepositoryImpl
import com.moneymanager.database.repository.AttributeTypeReadRepositoryImpl
import com.moneymanager.database.repository.AttributeTypeWriteRepositoryImpl
import com.moneymanager.database.repository.AuditReadRepositoryImpl
import com.moneymanager.database.repository.CategoryReadRepositoryImpl
import com.moneymanager.database.repository.CategoryWriteRepositoryImpl
import com.moneymanager.database.repository.CsvAccountMappingReadRepositoryImpl
import com.moneymanager.database.repository.CsvAccountMappingWriteRepositoryImpl
import com.moneymanager.database.repository.CsvImportReadRepositoryImpl
import com.moneymanager.database.repository.CsvImportStrategyReadRepositoryImpl
import com.moneymanager.database.repository.CsvImportStrategyWriteRepositoryImpl
import com.moneymanager.database.repository.CsvImportWriteRepositoryImpl
import com.moneymanager.database.repository.CurrencyReadRepositoryImpl
import com.moneymanager.database.repository.CurrencyWriteRepositoryImpl
import com.moneymanager.database.repository.DeviceReadRepositoryImpl
import com.moneymanager.database.repository.DeviceWriteRepositoryImpl
import com.moneymanager.database.repository.PersonAccountOwnershipReadRepositoryImpl
import com.moneymanager.database.repository.PersonAccountOwnershipWriteRepositoryImpl
import com.moneymanager.database.repository.PersonAttributeReadRepositoryImpl
import com.moneymanager.database.repository.PersonAttributeWriteRepositoryImpl
import com.moneymanager.database.repository.PersonReadRepositoryImpl
import com.moneymanager.database.repository.PersonWriteRepositoryImpl
import com.moneymanager.database.repository.QifImportReadRepositoryImpl
import com.moneymanager.database.repository.QifImportWriteRepositoryImpl
import com.moneymanager.database.repository.RelationshipTypeReadRepositoryImpl
import com.moneymanager.database.repository.RelationshipTypeWriteRepositoryImpl
import com.moneymanager.database.repository.SettingsReadRepositoryImpl
import com.moneymanager.database.repository.SettingsWriteRepositoryImpl
import com.moneymanager.database.repository.TransactionReadRepositoryImpl
import com.moneymanager.database.repository.TransactionWriteRepositoryImpl
import com.moneymanager.database.repository.TransferAttributeReadRepositoryImpl
import com.moneymanager.database.repository.TransferAttributeWriteRepositoryImpl
import com.moneymanager.database.repository.TransferRelationshipReadRepositoryImpl
import com.moneymanager.database.repository.TransferRelationshipWriteRepositoryImpl
import com.moneymanager.database.repository.TransferSourceReadRepositoryImpl
import com.moneymanager.database.repository.TransferSourceWriteRepositoryImpl
import com.moneymanager.database.service.CsvStrategyExportService
import com.moneymanager.di.DatabaseScope
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.repository.AccountAttributeReadRepository
import com.moneymanager.domain.repository.AccountAttributeWriteRepository
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.AccountWriteRepository
import com.moneymanager.domain.repository.ApiImportStrategyReadRepository
import com.moneymanager.domain.repository.ApiImportStrategyWriteRepository
import com.moneymanager.domain.repository.ApiSessionReadRepository
import com.moneymanager.domain.repository.ApiSessionWriteRepository
import com.moneymanager.domain.repository.AttributeTypeReadRepository
import com.moneymanager.domain.repository.AttributeTypeWriteRepository
import com.moneymanager.domain.repository.AuditReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.CategoryWriteRepository
import com.moneymanager.domain.repository.CsvAccountMappingReadRepository
import com.moneymanager.domain.repository.CsvAccountMappingWriteRepository
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.domain.repository.CsvImportStrategyReadRepository
import com.moneymanager.domain.repository.CsvImportStrategyWriteRepository
import com.moneymanager.domain.repository.CsvImportWriteRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.domain.repository.CurrencyWriteRepository
import com.moneymanager.domain.repository.DeviceReadRepository
import com.moneymanager.domain.repository.DeviceWriteRepository
import com.moneymanager.domain.repository.PersonAccountOwnershipReadRepository
import com.moneymanager.domain.repository.PersonAccountOwnershipWriteRepository
import com.moneymanager.domain.repository.PersonAttributeReadRepository
import com.moneymanager.domain.repository.PersonAttributeWriteRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.domain.repository.PersonWriteRepository
import com.moneymanager.domain.repository.QifImportReadRepository
import com.moneymanager.domain.repository.QifImportWriteRepository
import com.moneymanager.domain.repository.RelationshipTypeReadRepository
import com.moneymanager.domain.repository.RelationshipTypeWriteRepository
import com.moneymanager.domain.repository.SettingsReadRepository
import com.moneymanager.domain.repository.SettingsWriteRepository
import com.moneymanager.domain.repository.TransactionReadRepository
import com.moneymanager.domain.repository.TransactionWriteRepository
import com.moneymanager.domain.repository.TransferAttributeReadRepository
import com.moneymanager.domain.repository.TransferAttributeWriteRepository
import com.moneymanager.domain.repository.TransferRelationshipReadRepository
import com.moneymanager.domain.repository.TransferRelationshipWriteRepository
import com.moneymanager.domain.repository.TransferSourceReadRepository
import com.moneymanager.domain.repository.TransferSourceWriteRepository
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importer.ImportEngineImpl
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * Module that provides all repository implementations as read + write pairs. The write impl delegates
 * its read methods to the (shared, singleton) read impl, so a single underlying instance backs both.
 */
@ContributesTo(DatabaseScope::class)
interface RepositoryModule {
    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideAccountAttributeReadRepository(database: MoneyManagerDatabaseWrapper): AccountAttributeReadRepository =
        AccountAttributeReadRepositoryImpl(database)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideAccountAttributeWriteRepository(
        database: MoneyManagerDatabaseWrapper,
        reader: AccountAttributeReadRepository,
    ): AccountAttributeWriteRepository = AccountAttributeWriteRepositoryImpl(database, reader)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideAccountReadRepository(database: MoneyManagerDatabaseWrapper): AccountReadRepository = AccountReadRepositoryImpl(database)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideAccountWriteRepository(
        database: MoneyManagerDatabaseWrapper,
        deviceId: DeviceId,
        reader: AccountReadRepository,
    ): AccountWriteRepository = AccountWriteRepositoryImpl(database, deviceId, reader)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideApiImportStrategyReadRepository(database: MoneyManagerDatabaseWrapper): ApiImportStrategyReadRepository =
        ApiImportStrategyReadRepositoryImpl(database)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideApiImportStrategyWriteRepository(
        database: MoneyManagerDatabaseWrapper,
        deviceId: DeviceId,
        reader: ApiImportStrategyReadRepository,
    ): ApiImportStrategyWriteRepository = ApiImportStrategyWriteRepositoryImpl(database, deviceId, reader)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideApiSessionReadRepository(database: MoneyManagerDatabaseWrapper): ApiSessionReadRepository =
        ApiSessionReadRepositoryImpl(database)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideApiSessionWriteRepository(
        database: MoneyManagerDatabaseWrapper,
        reader: ApiSessionReadRepository,
    ): ApiSessionWriteRepository = ApiSessionWriteRepositoryImpl(database, reader)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideAttributeTypeReadRepository(database: MoneyManagerDatabaseWrapper): AttributeTypeReadRepository =
        AttributeTypeReadRepositoryImpl(database)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideAttributeTypeWriteRepository(
        database: MoneyManagerDatabaseWrapper,
        reader: AttributeTypeReadRepository,
    ): AttributeTypeWriteRepository = AttributeTypeWriteRepositoryImpl(database, reader)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideAuditRepository(database: MoneyManagerDatabaseWrapper): AuditReadRepository = AuditReadRepositoryImpl(database)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideCategoryReadRepository(database: MoneyManagerDatabaseWrapper): CategoryReadRepository = CategoryReadRepositoryImpl(database)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideCategoryWriteRepository(
        database: MoneyManagerDatabaseWrapper,
        deviceId: DeviceId,
        reader: CategoryReadRepository,
    ): CategoryWriteRepository = CategoryWriteRepositoryImpl(database, deviceId, reader)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideCsvAccountMappingReadRepository(database: MoneyManagerDatabaseWrapper): CsvAccountMappingReadRepository =
        CsvAccountMappingReadRepositoryImpl(database)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideCsvAccountMappingWriteRepository(
        database: MoneyManagerDatabaseWrapper,
        reader: CsvAccountMappingReadRepository,
    ): CsvAccountMappingWriteRepository = CsvAccountMappingWriteRepositoryImpl(database, reader)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideCsvImportReadRepository(database: MoneyManagerDatabaseWrapper): CsvImportReadRepository =
        CsvImportReadRepositoryImpl(database)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideCsvImportWriteRepository(
        database: MoneyManagerDatabaseWrapper,
        deviceId: DeviceId,
        reader: CsvImportReadRepository,
    ): CsvImportWriteRepository = CsvImportWriteRepositoryImpl(database, deviceId, reader)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideCsvImportStrategyReadRepository(database: MoneyManagerDatabaseWrapper): CsvImportStrategyReadRepository =
        CsvImportStrategyReadRepositoryImpl(database)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideCsvImportStrategyWriteRepository(
        database: MoneyManagerDatabaseWrapper,
        deviceId: DeviceId,
        reader: CsvImportStrategyReadRepository,
    ): CsvImportStrategyWriteRepository = CsvImportStrategyWriteRepositoryImpl(database, deviceId, reader)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideCurrencyReadRepository(database: MoneyManagerDatabaseWrapper): CurrencyReadRepository = CurrencyReadRepositoryImpl(database)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideCurrencyWriteRepository(
        database: MoneyManagerDatabaseWrapper,
        deviceId: DeviceId,
        reader: CurrencyReadRepository,
    ): CurrencyWriteRepository = CurrencyWriteRepositoryImpl(database, deviceId, reader)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideDeviceReadRepository(database: MoneyManagerDatabaseWrapper): DeviceReadRepository = DeviceReadRepositoryImpl(database)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideDeviceWriteRepository(
        database: MoneyManagerDatabaseWrapper,
        reader: DeviceReadRepository,
    ): DeviceWriteRepository = DeviceWriteRepositoryImpl(database, reader)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun providePersonAttributeReadRepository(database: MoneyManagerDatabaseWrapper): PersonAttributeReadRepository =
        PersonAttributeReadRepositoryImpl(database)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun providePersonAttributeWriteRepository(
        database: MoneyManagerDatabaseWrapper,
        reader: PersonAttributeReadRepository,
    ): PersonAttributeWriteRepository = PersonAttributeWriteRepositoryImpl(database, reader)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun providePersonReadRepository(database: MoneyManagerDatabaseWrapper): PersonReadRepository = PersonReadRepositoryImpl(database)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun providePersonWriteRepository(
        database: MoneyManagerDatabaseWrapper,
        deviceId: DeviceId,
        reader: PersonReadRepository,
    ): PersonWriteRepository = PersonWriteRepositoryImpl(database, deviceId, reader)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun providePersonAccountOwnershipReadRepository(database: MoneyManagerDatabaseWrapper): PersonAccountOwnershipReadRepository =
        PersonAccountOwnershipReadRepositoryImpl(database)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun providePersonAccountOwnershipWriteRepository(
        database: MoneyManagerDatabaseWrapper,
        deviceId: DeviceId,
        reader: PersonAccountOwnershipReadRepository,
    ): PersonAccountOwnershipWriteRepository = PersonAccountOwnershipWriteRepositoryImpl(database, deviceId, reader)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideQifImportReadRepository(database: MoneyManagerDatabaseWrapper): QifImportReadRepository =
        QifImportReadRepositoryImpl(database)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideQifImportWriteRepository(
        database: MoneyManagerDatabaseWrapper,
        deviceId: DeviceId,
        reader: QifImportReadRepository,
    ): QifImportWriteRepository = QifImportWriteRepositoryImpl(database, deviceId, reader)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideRelationshipTypeReadRepository(database: MoneyManagerDatabaseWrapper): RelationshipTypeReadRepository =
        RelationshipTypeReadRepositoryImpl(database)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideRelationshipTypeWriteRepository(
        database: MoneyManagerDatabaseWrapper,
        reader: RelationshipTypeReadRepository,
    ): RelationshipTypeWriteRepository = RelationshipTypeWriteRepositoryImpl(database, reader)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideSettingsReadRepository(database: MoneyManagerDatabaseWrapper): SettingsReadRepository = SettingsReadRepositoryImpl(database)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideSettingsWriteRepository(
        database: MoneyManagerDatabaseWrapper,
        reader: SettingsReadRepository,
    ): SettingsWriteRepository = SettingsWriteRepositoryImpl(database, reader)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideMaintenanceService(database: MoneyManagerDatabaseWrapper): DatabaseMaintenanceService =
        DatabaseMaintenanceServiceImpl(database)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideCsvStrategyExportService(
        accountRepository: AccountReadRepository,
        currencyRepository: CurrencyReadRepository,
        categoryRepository: CategoryReadRepository,
        importEngine: ImportEngine,
    ): CsvStrategyExportService = CsvStrategyExportService(accountRepository, currencyRepository, categoryRepository, importEngine)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideTransactionReadRepository(database: MoneyManagerDatabaseWrapper): TransactionReadRepository =
        TransactionReadRepositoryImpl(database)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideTransactionWriteRepository(
        database: MoneyManagerDatabaseWrapper,
        deviceId: DeviceId,
        reader: TransactionReadRepository,
    ): TransactionWriteRepository = TransactionWriteRepositoryImpl(database, deviceId, reader)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideTransferAttributeReadRepository(database: MoneyManagerDatabaseWrapper): TransferAttributeReadRepository =
        TransferAttributeReadRepositoryImpl(database)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideTransferAttributeWriteRepository(
        database: MoneyManagerDatabaseWrapper,
        reader: TransferAttributeReadRepository,
    ): TransferAttributeWriteRepository = TransferAttributeWriteRepositoryImpl(database, reader)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideTransferRelationshipReadRepository(database: MoneyManagerDatabaseWrapper): TransferRelationshipReadRepository =
        TransferRelationshipReadRepositoryImpl(database)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideTransferRelationshipWriteRepository(
        database: MoneyManagerDatabaseWrapper,
        reader: TransferRelationshipReadRepository,
    ): TransferRelationshipWriteRepository = TransferRelationshipWriteRepositoryImpl(database, reader)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideTransferSourceReadRepository(database: MoneyManagerDatabaseWrapper): TransferSourceReadRepository =
        TransferSourceReadRepositoryImpl(database)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideTransferSourceWriteRepository(
        database: MoneyManagerDatabaseWrapper,
        deviceRepository: DeviceWriteRepository,
        reader: TransferSourceReadRepository,
    ): TransferSourceWriteRepository = TransferSourceWriteRepositoryImpl(database, deviceRepository, reader)

    @Provides
    @SingleIn(DatabaseScope::class)
    fun provideImportEngine(
        transactionRepository: TransactionWriteRepository,
        accountRepository: AccountWriteRepository,
        accountAttributeRepository: AccountAttributeWriteRepository,
        personRepository: PersonWriteRepository,
        personAttributeRepository: PersonAttributeWriteRepository,
        ownershipRepository: PersonAccountOwnershipWriteRepository,
        categoryRepository: CategoryWriteRepository,
        currencyRepository: CurrencyWriteRepository,
        attributeTypeRepository: AttributeTypeWriteRepository,
        relationshipTypeRepository: RelationshipTypeWriteRepository,
        csvImportStrategyRepository: CsvImportStrategyWriteRepository,
        apiImportStrategyRepository: ApiImportStrategyWriteRepository,
        csvAccountMappingRepository: CsvAccountMappingWriteRepository,
        csvImportRepository: CsvImportWriteRepository,
        qifImportRepository: QifImportWriteRepository,
        apiSessionRepository: ApiSessionWriteRepository,
        settingsRepository: SettingsWriteRepository,
    ): ImportEngine =
        ImportEngineImpl(
            transactionRepository = transactionRepository,
            accountRepository = accountRepository,
            accountAttributeRepository = accountAttributeRepository,
            personRepository = personRepository,
            personAttributeRepository = personAttributeRepository,
            ownershipRepository = ownershipRepository,
            categoryRepository = categoryRepository,
            currencyRepository = currencyRepository,
            attributeTypeRepository = attributeTypeRepository,
            relationshipTypeRepository = relationshipTypeRepository,
            csvImportStrategyRepository = csvImportStrategyRepository,
            apiImportStrategyRepository = apiImportStrategyRepository,
            csvAccountMappingRepository = csvAccountMappingRepository,
            csvImportRepository = csvImportRepository,
            qifImportRepository = qifImportRepository,
            apiSessionRepository = apiSessionRepository,
            settingsRepository = settingsRepository,
        )
}
