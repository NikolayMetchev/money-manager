package com.moneymanager.database

import com.moneymanager.database.service.AccountMappingExportService
import com.moneymanager.database.service.CsvStrategyExportService
import com.moneymanager.domain.CsvStrategyImportExport
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.StrategyLibrary
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.repository.AccountAttributeWriteRepository
import com.moneymanager.domain.repository.AccountMappingWriteRepository
import com.moneymanager.domain.repository.AccountWriteRepository
import com.moneymanager.domain.repository.ApiImportStrategyWriteRepository
import com.moneymanager.domain.repository.ApiSessionWriteRepository
import com.moneymanager.domain.repository.AttributeTypeWriteRepository
import com.moneymanager.domain.repository.AuditReadRepository
import com.moneymanager.domain.repository.CategoryWriteRepository
import com.moneymanager.domain.repository.CryptoWriteRepository
import com.moneymanager.domain.repository.CsvImportStrategyWriteRepository
import com.moneymanager.domain.repository.CsvImportWriteRepository
import com.moneymanager.domain.repository.CurrencyWriteRepository
import com.moneymanager.domain.repository.DeviceReadRepository
import com.moneymanager.domain.repository.ImportDirectoryReadRepository
import com.moneymanager.domain.repository.ImportTimelineReadRepository
import com.moneymanager.domain.repository.PassThroughAccountReadRepository
import com.moneymanager.domain.repository.PersonAccountOwnershipWriteRepository
import com.moneymanager.domain.repository.PersonAttributeWriteRepository
import com.moneymanager.domain.repository.PersonWriteRepository
import com.moneymanager.domain.repository.QifImportWriteRepository
import com.moneymanager.domain.repository.RelationshipTypeWriteRepository
import com.moneymanager.domain.repository.SettingsWriteRepository
import com.moneymanager.domain.repository.TradeWriteRepository
import com.moneymanager.domain.repository.TransactionWriteRepository
import com.moneymanager.domain.repository.TransferRelationshipReadRepository
import com.moneymanager.domain.repository.TransferSourceReadRepository

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
    val accountRepository: AccountWriteRepository,
    val accountAttributeRepository: AccountAttributeWriteRepository,
    val categoryRepository: CategoryWriteRepository,
    val currencyRepository: CurrencyWriteRepository,
    val cryptoRepository: CryptoWriteRepository,
)

data class Imports(
    val apiImportStrategyRepository: ApiImportStrategyWriteRepository,
    val apiSessionRepository: ApiSessionWriteRepository,
    val accountMappingRepository: AccountMappingWriteRepository,
    val csvImportRepository: CsvImportWriteRepository,
    val csvImportStrategyRepository: CsvImportStrategyWriteRepository,
    val csvStrategyExportService: CsvStrategyExportService,
    val csvStrategyImportExport: CsvStrategyImportExport,
    val accountMappingExportService: AccountMappingExportService,
    val strategyLibrary: StrategyLibrary,
    val qifImportRepository: QifImportWriteRepository,
    val importDirectoryRepository: ImportDirectoryReadRepository,
    val importTimelineRepository: ImportTimelineReadRepository,
    val passThroughAccountRepository: PassThroughAccountReadRepository,
    val maintenance: Maintenance,
)

data class Transactions(
    val transactionRepository: TransactionWriteRepository,
    val transferSourceRepository: TransferSourceReadRepository,
    val attributeTypeRepository: AttributeTypeWriteRepository,
    val relationshipTypeRepository: RelationshipTypeWriteRepository,
    val transferRelationshipRepository: TransferRelationshipReadRepository,
    val tradeRepository: TradeWriteRepository,
)

data class People(
    val personRepository: PersonWriteRepository,
    val personAccountOwnershipRepository: PersonAccountOwnershipWriteRepository,
    val personAttributeRepository: PersonAttributeWriteRepository,
)

data class Settings(
    val settingsRepository: SettingsWriteRepository,
    val deviceRepository: DeviceReadRepository,
)

data class Audit(
    val auditRepository: AuditReadRepository,
)
