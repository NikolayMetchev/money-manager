package com.moneymanager.database

import com.moneymanager.database.service.CsvStrategyExportService
import com.moneymanager.domain.CsvStrategyImportExport
import com.moneymanager.domain.EntitySource
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.repository.AccountAttributeRepository
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.ApiImportStrategyRepository
import com.moneymanager.domain.repository.ApiSessionRepository
import com.moneymanager.domain.repository.AttributeTypeRepository
import com.moneymanager.domain.repository.AuditRepository
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.domain.repository.CsvAccountMappingRepository
import com.moneymanager.domain.repository.CsvImportRepository
import com.moneymanager.domain.repository.CsvImportStrategyRepository
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.domain.repository.DeviceRepository
import com.moneymanager.domain.repository.PersonAccountOwnershipRepository
import com.moneymanager.domain.repository.PersonAttributeRepository
import com.moneymanager.domain.repository.PersonRepository
import com.moneymanager.domain.repository.QifImportRepository
import com.moneymanager.domain.repository.SettingsRepository
import com.moneymanager.domain.repository.TransactionRepository
import com.moneymanager.domain.repository.TransferSourceRepository

data class Application(
    val accounts: Accounts,
    val imports: Imports,
    val transactions: Transactions,
    val people: People,
    val settings: Settings,
    val audit: Audit,
    val deviceId: DeviceId,
)

data class Accounts(
    val accountRepository: AccountRepository,
    val accountAttributeRepository: AccountAttributeRepository,
    val categoryRepository: CategoryRepository,
    val currencyRepository: CurrencyRepository,
)

data class Imports(
    val apiImportStrategyRepository: ApiImportStrategyRepository,
    val apiSessionRepository: ApiSessionRepository,
    val csvAccountMappingRepository: CsvAccountMappingRepository,
    val csvImportRepository: CsvImportRepository,
    val csvImportStrategyRepository: CsvImportStrategyRepository,
    val csvStrategyExportService: CsvStrategyExportService,
    val csvStrategyImportExport: CsvStrategyImportExport,
    val qifImportRepository: QifImportRepository,
    val maintenance: Maintenance,
)

data class Transactions(
    val transactionRepository: TransactionRepository,
    val transferSourceRepository: TransferSourceRepository,
    val attributeTypeRepository: AttributeTypeRepository,
    val entitySource: EntitySource,
    val sampleEntitySource: EntitySource,
)

data class People(
    val personRepository: PersonRepository,
    val personAccountOwnershipRepository: PersonAccountOwnershipRepository,
    val personAttributeRepository: PersonAttributeRepository,
)

data class Settings(
    val settingsRepository: SettingsRepository,
    val deviceRepository: DeviceRepository,
)

data class Audit(
    val auditRepository: AuditRepository,
)
