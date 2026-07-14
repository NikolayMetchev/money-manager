package com.moneymanager.database.di

import com.moneymanager.database.DatabaseMaintenanceService
import com.moneymanager.database.service.AccountMappingExportService
import com.moneymanager.database.service.CsvStrategyExportService
import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.di.scope.DatabaseScope
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.repository.AuditReadRepository
import com.moneymanager.domain.repository.ImportTimelineReadRepository
import com.moneymanager.domain.repository.write.AccountAttributeWriteRepository
import com.moneymanager.domain.repository.write.AccountMappingWriteRepository
import com.moneymanager.domain.repository.write.AccountWriteRepository
import com.moneymanager.domain.repository.write.ApiImportStrategyWriteRepository
import com.moneymanager.domain.repository.write.ApiSessionWriteRepository
import com.moneymanager.domain.repository.write.AttributeTypeWriteRepository
import com.moneymanager.domain.repository.write.CategoryWriteRepository
import com.moneymanager.domain.repository.write.CryptoWriteRepository
import com.moneymanager.domain.repository.write.CsvImportStrategyWriteRepository
import com.moneymanager.domain.repository.write.CsvImportWriteRepository
import com.moneymanager.domain.repository.write.CurrencyWriteRepository
import com.moneymanager.domain.repository.write.DeviceWriteRepository
import com.moneymanager.domain.repository.write.ExchangeOrderWriteRepository
import com.moneymanager.domain.repository.write.ImportDirectoryWriteRepository
import com.moneymanager.domain.repository.write.PassThroughAccountWriteRepository
import com.moneymanager.domain.repository.write.PersonAccountOwnershipWriteRepository
import com.moneymanager.domain.repository.write.PersonAttributeWriteRepository
import com.moneymanager.domain.repository.write.PersonWriteRepository
import com.moneymanager.domain.repository.write.QifImportWriteRepository
import com.moneymanager.domain.repository.write.RelationshipTypeWriteRepository
import com.moneymanager.domain.repository.write.SettingsWriteRepository
import com.moneymanager.domain.repository.write.TradeWriteRepository
import com.moneymanager.domain.repository.write.TransactionWriteRepository
import com.moneymanager.domain.repository.write.TransferAttributeWriteRepository
import com.moneymanager.domain.repository.write.TransferRelationshipWriteRepository
import com.moneymanager.domain.repository.write.TransferSourceWriteRepository
import com.moneymanager.domain.strategy.StrategyLibrary
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
