package com.moneymanager.ui

import com.moneymanager.database.Application
import com.moneymanager.database.service.CsvStrategyExportService
import com.moneymanager.domain.CsvStrategyImportExport
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.repository.AccountAttributeReadRepository
import com.moneymanager.domain.repository.AccountWriteRepository
import com.moneymanager.domain.repository.ApiImportStrategyWriteRepository
import com.moneymanager.domain.repository.ApiSessionWriteRepository
import com.moneymanager.domain.repository.AttributeTypeWriteRepository
import com.moneymanager.domain.repository.AuditReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.CsvAccountMappingWriteRepository
import com.moneymanager.domain.repository.CsvImportStrategyWriteRepository
import com.moneymanager.domain.repository.CsvImportWriteRepository
import com.moneymanager.domain.repository.CurrencyWriteRepository
import com.moneymanager.domain.repository.DeviceReadRepository
import com.moneymanager.domain.repository.PersonAccountOwnershipReadRepository
import com.moneymanager.domain.repository.PersonAttributeReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.domain.repository.QifImportWriteRepository
import com.moneymanager.domain.repository.SettingsWriteRepository
import com.moneymanager.domain.repository.TransactionReadRepository
import com.moneymanager.domain.repository.TransferSourceReadRepository
import com.moneymanager.importengineapi.EditGate
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importer.ImportEngineImpl

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
    val accountRepository: AccountWriteRepository,
    val accountAttributeRepository: AccountAttributeReadRepository,
    val categoryRepository: CategoryReadRepository,
    val currencyRepository: CurrencyWriteRepository,
)

data class ImportsDomain(
    val apiImportStrategyRepository: ApiImportStrategyWriteRepository,
    val apiSessionRepository: ApiSessionWriteRepository,
    val csvAccountMappingRepository: CsvAccountMappingWriteRepository,
    val csvImportRepository: CsvImportWriteRepository,
    val csvImportStrategyRepository: CsvImportStrategyWriteRepository,
    val csvStrategyExportService: CsvStrategyExportService,
    val csvStrategyImportExport: CsvStrategyImportExport,
    val qifImportRepository: QifImportWriteRepository,
    val maintenance: Maintenance,
)

data class TransactionsDomain(
    val transactionRepository: TransactionReadRepository,
    val transferSourceRepository: TransferSourceReadRepository,
    val attributeTypeRepository: AttributeTypeWriteRepository,
    val importEngine: ImportEngine,
)

data class PeopleDomain(
    val personRepository: PersonReadRepository,
    val personAccountOwnershipRepository: PersonAccountOwnershipReadRepository,
    val personAttributeRepository: PersonAttributeReadRepository,
)

data class SettingsDomain(
    val settingsRepository: SettingsWriteRepository,
    val deviceRepository: DeviceReadRepository,
)

data class AuditDomain(
    val auditRepository: AuditReadRepository,
)

fun Application.toAppServices(editGate: EditGate = EditGate.AlwaysWritable) =
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
                csvStrategyImportExport = imports.csvStrategyImportExport,
                qifImportRepository = imports.qifImportRepository,
                maintenance = imports.maintenance,
            ),
        transactions =
            TransactionsDomain(
                transactionRepository = transactions.transactionRepository,
                transferSourceRepository = transactions.transferSourceRepository,
                attributeTypeRepository = transactions.attributeTypeRepository,
                importEngine =
                    ImportEngineImpl(
                        transactionRepository = transactions.transactionRepository,
                        accountRepository = accounts.accountRepository,
                        accountAttributeRepository = accounts.accountAttributeRepository,
                        personRepository = people.personRepository,
                        personAttributeRepository = people.personAttributeRepository,
                        ownershipRepository = people.personAccountOwnershipRepository,
                        categoryRepository = accounts.categoryRepository,
                        editGate = editGate,
                    ),
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
