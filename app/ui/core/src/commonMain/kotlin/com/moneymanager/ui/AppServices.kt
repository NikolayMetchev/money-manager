package com.moneymanager.ui

import com.moneymanager.database.Application
import com.moneymanager.database.service.AccountMappingExportService
import com.moneymanager.database.service.CsvStrategyExportService
import com.moneymanager.domain.CsvStrategyImportExport
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.repository.AccountAttributeReadRepository
import com.moneymanager.domain.repository.AccountMappingReadRepository
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.ApiImportStrategyReadRepository
import com.moneymanager.domain.repository.ApiSessionReadRepository
import com.moneymanager.domain.repository.AttributeTypeReadRepository
import com.moneymanager.domain.repository.AuditReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.domain.repository.CsvImportStrategyReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.domain.repository.DeviceReadRepository
import com.moneymanager.domain.repository.ImportDirectoryReadRepository
import com.moneymanager.domain.repository.PassThroughAccountReadRepository
import com.moneymanager.domain.repository.PersonAccountOwnershipReadRepository
import com.moneymanager.domain.repository.PersonAttributeReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.domain.repository.QifImportReadRepository
import com.moneymanager.domain.repository.SettingsReadRepository
import com.moneymanager.domain.repository.TransactionReadRepository
import com.moneymanager.domain.repository.TransferSourceReadRepository
import com.moneymanager.importengineapi.ImportEngine

/**
 * The UI's view of the database. Every repository here is a **read** interface — the UI never mutates
 * the database through a repository. All writes go through [TransactionsDomain.importEngine] (the single
 * write seam, which carries the session's edit gate). The engine is built in di/core
 * ([com.moneymanager.di.database.createImportEngine]) so the write repositories never reach this layer.
 */
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
    val accountRepository: AccountReadRepository,
    val accountAttributeRepository: AccountAttributeReadRepository,
    val categoryRepository: CategoryReadRepository,
    val currencyRepository: CurrencyReadRepository,
)

data class ImportsDomain(
    val apiImportStrategyRepository: ApiImportStrategyReadRepository,
    val apiSessionRepository: ApiSessionReadRepository,
    val accountMappingRepository: AccountMappingReadRepository,
    val csvImportRepository: CsvImportReadRepository,
    val csvImportStrategyRepository: CsvImportStrategyReadRepository,
    val csvStrategyExportService: CsvStrategyExportService,
    val csvStrategyImportExport: CsvStrategyImportExport,
    val accountMappingExportService: AccountMappingExportService,
    val qifImportRepository: QifImportReadRepository,
    val importDirectoryRepository: ImportDirectoryReadRepository,
    val passThroughAccountRepository: PassThroughAccountReadRepository,
    val maintenance: Maintenance,
)

data class TransactionsDomain(
    val transactionRepository: TransactionReadRepository,
    val transferSourceRepository: TransferSourceReadRepository,
    val attributeTypeRepository: AttributeTypeReadRepository,
    val importEngine: ImportEngine,
)

data class PeopleDomain(
    val personRepository: PersonReadRepository,
    val personAccountOwnershipRepository: PersonAccountOwnershipReadRepository,
    val personAttributeRepository: PersonAttributeReadRepository,
)

data class SettingsDomain(
    val settingsRepository: SettingsReadRepository,
    val deviceRepository: DeviceReadRepository,
)

data class AuditDomain(
    val auditRepository: AuditReadRepository,
)

/**
 * Narrows the db-layer [Application] (whose repositories are write interfaces) to the UI's read-only
 * [AppServices], plugging in the already-constructed [importEngine] as the single write seam.
 */
fun Application.toAppServices(importEngine: ImportEngine) =
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
                accountMappingRepository = imports.accountMappingRepository,
                csvImportRepository = imports.csvImportRepository,
                csvImportStrategyRepository = imports.csvImportStrategyRepository,
                csvStrategyExportService = imports.csvStrategyExportService,
                csvStrategyImportExport = imports.csvStrategyImportExport,
                accountMappingExportService = imports.accountMappingExportService,
                qifImportRepository = imports.qifImportRepository,
                importDirectoryRepository = imports.importDirectoryRepository,
                passThroughAccountRepository = imports.passThroughAccountRepository,
                maintenance = imports.maintenance,
            ),
        transactions =
            TransactionsDomain(
                transactionRepository = transactions.transactionRepository,
                transferSourceRepository = transactions.transferSourceRepository,
                attributeTypeRepository = transactions.attributeTypeRepository,
                importEngine = importEngine,
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
