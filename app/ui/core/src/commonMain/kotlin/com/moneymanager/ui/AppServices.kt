package com.moneymanager.ui

import com.moneymanager.database.ApplicationGraph
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

data class AppServices(
    val accounts: AccountsDomain,
    val imports: ImportsDomain,
    val transactions: TransactionsDomain,
    val people: PeopleDomain,
    val settings: SettingsDomain,
    val audit: AuditDomain,
    val deviceId: DeviceId,
)

data class AccountsDomain(
    val accountRepository: AccountRepository,
    val accountAttributeRepository: AccountAttributeRepository,
    val categoryRepository: CategoryRepository,
    val currencyRepository: CurrencyRepository,
)

data class ImportsDomain(
    val apiImportStrategyRepository: ApiImportStrategyRepository,
    val apiSessionRepository: ApiSessionRepository,
    val csvAccountMappingRepository: CsvAccountMappingRepository,
    val csvImportRepository: CsvImportRepository,
    val csvImportStrategyRepository: CsvImportStrategyRepository,
    val csvStrategyExportService: CsvStrategyExportService,
    val csvStrategyImportExportPort: CsvStrategyImportExportPort,
    val maintenancePort: MaintenancePort,
)

data class TransactionsDomain(
    val transactionRepository: TransactionRepository,
    val transferSourceRepository: TransferSourceRepository,
    val attributeTypeRepository: AttributeTypeRepository,
    val entitySourcePort: EntitySourcePort,
    val transferSourcePort: TransferSourcePort,
    val sampleEntitySourcePort: EntitySourcePort,
)

data class PeopleDomain(
    val personRepository: PersonRepository,
    val personAccountOwnershipRepository: PersonAccountOwnershipRepository,
    val personAttributeRepository: PersonAttributeRepository,
)

data class SettingsDomain(
    val settingsRepository: SettingsRepository,
    val deviceRepository: DeviceRepository,
)

data class AuditDomain(
    val auditRepository: AuditRepository,
)

fun ApplicationGraph.toAppServices() =
    AppServices(
        accounts =
            AccountsDomain(
                accountRepository = accounts.accountRepository,
                accountAttributeRepository = accounts.accountAttributeRepository,
                categoryRepository = accounts.categoryRepository,
                currencyRepository = accounts.currencyRepository,
            ),
        imports =
            ImportsDomain(
                apiImportStrategyRepository = imports.apiImportStrategyRepository,
                apiSessionRepository = imports.apiSessionRepository,
                csvAccountMappingRepository = imports.csvAccountMappingRepository,
                csvImportRepository = imports.csvImportRepository,
                csvImportStrategyRepository = imports.csvImportStrategyRepository,
                csvStrategyExportService = imports.csvStrategyExportService,
                csvStrategyImportExportPort = imports.csvStrategyImportExportPort,
                maintenancePort = imports.maintenancePort,
            ),
        transactions =
            TransactionsDomain(
                transactionRepository = transactions.transactionRepository,
                transferSourceRepository = transactions.transferSourceRepository,
                attributeTypeRepository = transactions.attributeTypeRepository,
                entitySourcePort = transactions.entitySourcePort,
                transferSourcePort = transactions.transferSourcePort,
                sampleEntitySourcePort = transactions.sampleEntitySourcePort,
            ),
        people =
            PeopleDomain(
                personRepository = people.personRepository,
                personAccountOwnershipRepository = people.personAccountOwnershipRepository,
                personAttributeRepository = people.personAttributeRepository,
            ),
        settings =
            SettingsDomain(
                settingsRepository = settings.settingsRepository,
                deviceRepository = settings.deviceRepository,
            ),
        audit = AuditDomain(auditRepository = audit.auditRepository),
        deviceId = deviceId,
    )
