package com.moneymanager.database.di

import com.moneymanager.database.service.AccountMappingExportService
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
import com.moneymanager.domain.repository.CryptoReadRepository
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.domain.repository.CsvImportStrategyReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.domain.repository.DeviceReadRepository
import com.moneymanager.domain.repository.ExchangeOrderReadRepository
import com.moneymanager.domain.repository.HolidayCalendarReadRepository
import com.moneymanager.domain.repository.ImportDirectoryReadRepository
import com.moneymanager.domain.repository.ImportTimelineReadRepository
import com.moneymanager.domain.repository.PassThroughAccountReadRepository
import com.moneymanager.domain.repository.PersonAccountOwnershipReadRepository
import com.moneymanager.domain.repository.PersonAttributeReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.domain.repository.ReconciliationReadRepository
import com.moneymanager.domain.repository.SettingsReadRepository
import com.moneymanager.domain.repository.TradeReadRepository
import com.moneymanager.domain.repository.TransactionReadRepository
import com.moneymanager.domain.repository.TransferRelationshipReadRepository
import com.moneymanager.domain.repository.TransferSourceReadRepository
import com.moneymanager.domain.strategy.CsvStrategyImportExport
import com.moneymanager.domain.strategy.StrategyLibrary
import com.moneymanager.importengineapi.ImportEngine

/**
 * The read-only view of an open database that the UI and the CLI receive. Every repository here is a
 * **read** interface, so nothing above this layer can mutate the database through a repository. All
 * writes go through [TransactionsDomain.importEngine] (the single write seam, which carries the session's
 * edit gate), built by [createImportEngine] so the write repositories never leave this module.
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
    val cryptoRepository: CryptoReadRepository,
)

data class ImportsDomain(
    val apiImportStrategyRepository: ApiImportStrategyReadRepository,
    val apiSessionRepository: ApiSessionReadRepository,
    val accountMappingRepository: AccountMappingReadRepository,
    val csvImportRepository: CsvImportReadRepository,
    val csvImportStrategyRepository: CsvImportStrategyReadRepository,
    val csvStrategyImportExport: CsvStrategyImportExport,
    val accountMappingExportService: AccountMappingExportService,
    val strategyLibrary: StrategyLibrary,
    val importDirectoryRepository: ImportDirectoryReadRepository,
    val importTimelineRepository: ImportTimelineReadRepository,
    val reconciliationRepository: ReconciliationReadRepository,
    val passThroughAccountRepository: PassThroughAccountReadRepository,
    val maintenance: Maintenance,
)

data class TransactionsDomain(
    val transactionRepository: TransactionReadRepository,
    val transferSourceRepository: TransferSourceReadRepository,
    val attributeTypeRepository: AttributeTypeReadRepository,
    val transferRelationshipRepository: TransferRelationshipReadRepository,
    val tradeRepository: TradeReadRepository,
    val exchangeOrderRepository: ExchangeOrderReadRepository,
    val importEngine: ImportEngine,
)

data class PeopleDomain(
    val personRepository: PersonReadRepository,
    val personAccountOwnershipRepository: PersonAccountOwnershipReadRepository,
    val personAttributeRepository: PersonAttributeReadRepository,
)

data class SettingsDomain(
    val settingsRepository: SettingsReadRepository,
    val holidayCalendarRepository: HolidayCalendarReadRepository,
    val deviceRepository: DeviceReadRepository,
)

data class AuditDomain(
    val auditRepository: AuditReadRepository,
)

/**
 * Narrows the database component (whose repositories are write interfaces) to the read-only
 * [AppServices], plugging in the already-constructed [importEngine] as the single write seam.
 */
fun DatabaseComponent.toAppServices(importEngine: ImportEngine) =
    AppServices(
        accounts =
            AccountsDomain(
                accountRepository = accountRepository,
                accountAttributeRepository = accountAttributeRepository,
                categoryRepository = categoryRepository,
                currencyRepository = currencyRepository,
                cryptoRepository = cryptoRepository,
            ),
        imports =
            ImportsDomain(
                apiImportStrategyRepository = apiImportStrategyRepository,
                apiSessionRepository = apiSessionRepository,
                accountMappingRepository = accountMappingRepository,
                csvImportRepository = csvImportRepository,
                csvImportStrategyRepository = csvImportStrategyRepository,
                csvStrategyImportExport = csvStrategyExportService,
                accountMappingExportService = accountMappingExportService,
                strategyLibrary = strategyLibrary,
                importDirectoryRepository = importDirectoryRepository,
                importTimelineRepository = importTimelineRepository,
                reconciliationRepository = reconciliationLinkRepository,
                passThroughAccountRepository = passThroughAccountRepository,
                maintenance = maintenanceService,
            ),
        transactions =
            TransactionsDomain(
                transactionRepository = transactionRepository,
                transferSourceRepository = transferSourceRepository,
                attributeTypeRepository = attributeTypeRepository,
                transferRelationshipRepository = transferRelationshipRepository,
                tradeRepository = tradeRepository,
                exchangeOrderRepository = exchangeOrderRepository,
                importEngine = importEngine,
            ),
        people =
            PeopleDomain(
                personRepository = personRepository,
                personAccountOwnershipRepository = personAccountOwnershipRepository,
                personAttributeRepository = personAttributeRepository,
            ),
        settings =
            SettingsDomain(
                settingsRepository = settingsRepository,
                holidayCalendarRepository = holidayCalendarRepository,
                deviceRepository = deviceRepository,
            ),
        audit = AuditDomain(auditRepository = auditRepository),
        deviceId = deviceId,
    )
