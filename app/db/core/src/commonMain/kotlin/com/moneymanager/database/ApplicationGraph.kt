package com.moneymanager.database

import com.moneymanager.database.service.CsvStrategyExportService
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.port.CsvStrategyImportExportPort
import com.moneymanager.domain.port.EntitySourcePort
import com.moneymanager.domain.port.MaintenancePort
import com.moneymanager.domain.port.TransferSourcePort
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
import com.moneymanager.domain.repository.SettingsRepository
import com.moneymanager.domain.repository.TransactionRepository
import com.moneymanager.domain.repository.TransferSourceRepository

data class ApplicationGraph(
    val accounts: AccountsGraph,
    val imports: ImportsGraph,
    val transactions: TransactionsGraph,
    val people: PeopleGraph,
    val settings: SettingsGraph,
    val audit: AuditGraph,
    val deviceId: DeviceId,
)

data class AccountsGraph(
    val accountRepository: AccountRepository,
    val accountAttributeRepository: AccountAttributeRepository,
    val categoryRepository: CategoryRepository,
    val currencyRepository: CurrencyRepository,
)

data class ImportsGraph(
    val apiImportStrategyRepository: ApiImportStrategyRepository,
    val apiSessionRepository: ApiSessionRepository,
    val csvAccountMappingRepository: CsvAccountMappingRepository,
    val csvImportRepository: CsvImportRepository,
    val csvImportStrategyRepository: CsvImportStrategyRepository,
    val csvStrategyExportService: CsvStrategyExportService,
    val csvStrategyImportExportPort: CsvStrategyImportExportPort,
    val maintenancePort: MaintenancePort,
)

data class TransactionsGraph(
    val transactionRepository: TransactionRepository,
    val transferSourceRepository: TransferSourceRepository,
    val attributeTypeRepository: AttributeTypeRepository,
    val entitySourcePort: EntitySourcePort,
    val transferSourcePort: TransferSourcePort,
    val sampleEntitySourcePort: EntitySourcePort,
)

data class PeopleGraph(
    val personRepository: PersonRepository,
    val personAccountOwnershipRepository: PersonAccountOwnershipRepository,
    val personAttributeRepository: PersonAttributeRepository,
)

data class SettingsGraph(
    val settingsRepository: SettingsRepository,
    val deviceRepository: DeviceRepository,
)

data class AuditGraph(
    val auditRepository: AuditRepository,
)
