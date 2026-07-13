package com.moneymanager.di.database

import com.moneymanager.database.DatabaseMaintenanceService
import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.database.service.AccountMappingExportService
import com.moneymanager.database.service.CsvStrategyExportService
import com.moneymanager.di.DatabaseScope
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
import com.moneymanager.domain.repository.DeviceWriteRepository
import com.moneymanager.domain.repository.ExchangeOrderWriteRepository
import com.moneymanager.domain.repository.ImportDirectoryWriteRepository
import com.moneymanager.domain.repository.ImportTimelineReadRepository
import com.moneymanager.domain.repository.PassThroughAccountWriteRepository
import com.moneymanager.domain.repository.PersonAccountOwnershipWriteRepository
import com.moneymanager.domain.repository.PersonAttributeWriteRepository
import com.moneymanager.domain.repository.PersonWriteRepository
import com.moneymanager.domain.repository.QifImportWriteRepository
import com.moneymanager.domain.repository.RelationshipTypeWriteRepository
import com.moneymanager.domain.repository.SettingsWriteRepository
import com.moneymanager.domain.repository.TradeWriteRepository
import com.moneymanager.domain.repository.TransactionWriteRepository
import com.moneymanager.domain.repository.TransferAttributeWriteRepository
import com.moneymanager.domain.repository.TransferRelationshipWriteRepository
import com.moneymanager.domain.repository.TransferSourceWriteRepository
import com.moneymanager.importengineapi.ImportEngine
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides

/**
 * DI component for database-dependent dependencies.
 * Created after a database is opened, providing repositories and device information.
 *
 * Repository accessors expose the **write** interface (which extends the read interface), so test
 * setup and the read-facing groupings can both pull from here; the read-only [AuditReadRepository]
 * has no write variant. App-facing groupings ([com.moneymanager.database.Application]) narrow the
 * entity repositories to their read interfaces so the UI cannot mutate them outside the ImportEngine.
 */
@DependencyGraph(DatabaseScope::class)
interface DatabaseComponent {
    val accountAttributeRepository: AccountAttributeWriteRepository
    val accountRepository: AccountWriteRepository
    val apiImportStrategyRepository: ApiImportStrategyWriteRepository
    val apiSessionRepository: ApiSessionWriteRepository
    val attributeTypeRepository: AttributeTypeWriteRepository
    val auditRepository: AuditReadRepository
    val categoryRepository: CategoryWriteRepository
    val accountMappingRepository: AccountMappingWriteRepository
    val csvImportRepository: CsvImportWriteRepository
    val csvImportStrategyRepository: CsvImportStrategyWriteRepository
    val csvStrategyExportService: CsvStrategyExportService
    val accountMappingExportService: AccountMappingExportService
    val strategyLibrary: StrategyLibrary
    val currencyRepository: CurrencyWriteRepository

    val cryptoRepository: CryptoWriteRepository

    val tradeRepository: TradeWriteRepository
    val exchangeOrderRepository: ExchangeOrderWriteRepository
    val deviceRepository: DeviceWriteRepository
    val importDirectoryRepository: ImportDirectoryWriteRepository
    val importTimelineRepository: ImportTimelineReadRepository
    val maintenanceService: DatabaseMaintenanceService
    val personAccountOwnershipRepository: PersonAccountOwnershipWriteRepository
    val personAttributeRepository: PersonAttributeWriteRepository
    val passThroughAccountRepository: PassThroughAccountWriteRepository
    val personRepository: PersonWriteRepository
    val qifImportRepository: QifImportWriteRepository
    val relationshipTypeRepository: RelationshipTypeWriteRepository
    val settingsRepository: SettingsWriteRepository
    val transactionRepository: TransactionWriteRepository
    val transferAttributeRepository: TransferAttributeWriteRepository
    val transferRelationshipRepository: TransferRelationshipWriteRepository
    val transferSourceRepository: TransferSourceWriteRepository
    val deviceId: DeviceId
    val importEngine: ImportEngine

    @DependencyGraph.Factory
    interface Factory {
        fun create(
            @Provides database: MoneyManagerDatabaseWrapper,
        ): DatabaseComponent
    }
}
