package com.moneymanager.di.database

import com.moneymanager.database.DatabaseMaintenanceService
import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.database.service.CsvStrategyExportService
import com.moneymanager.di.DatabaseScope
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
import com.moneymanager.domain.repository.RelationshipTypeRepository
import com.moneymanager.domain.repository.SettingsRepository
import com.moneymanager.domain.repository.TransactionRepository
import com.moneymanager.domain.repository.TransferAttributeRepository
import com.moneymanager.domain.repository.TransferRelationshipRepository
import com.moneymanager.domain.repository.TransferSourceRepository
import com.moneymanager.importengineapi.ImportEngine
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides

/**
 * DI component for database-dependent dependencies.
 * Created after a database is opened, providing repositories and device information.
 */
@DependencyGraph(DatabaseScope::class)
interface DatabaseComponent {
    val accountAttributeRepository: AccountAttributeRepository
    val accountRepository: AccountRepository
    val apiImportStrategyRepository: ApiImportStrategyRepository
    val apiSessionRepository: ApiSessionRepository
    val attributeTypeRepository: AttributeTypeRepository
    val auditRepository: AuditRepository
    val categoryRepository: CategoryRepository
    val csvAccountMappingRepository: CsvAccountMappingRepository
    val csvImportRepository: CsvImportRepository
    val csvImportStrategyRepository: CsvImportStrategyRepository
    val csvStrategyExportService: CsvStrategyExportService
    val currencyRepository: CurrencyRepository
    val deviceRepository: DeviceRepository
    val maintenanceService: DatabaseMaintenanceService
    val personAccountOwnershipRepository: PersonAccountOwnershipRepository
    val personAttributeRepository: PersonAttributeRepository
    val personRepository: PersonRepository
    val qifImportRepository: QifImportRepository
    val relationshipTypeRepository: RelationshipTypeRepository
    val settingsRepository: SettingsRepository
    val transactionRepository: TransactionRepository
    val transferAttributeRepository: TransferAttributeRepository
    val transferRelationshipRepository: TransferRelationshipRepository
    val transferSourceRepository: TransferSourceRepository
    val deviceId: DeviceId
    val importEngine: ImportEngine

    @DependencyGraph.Factory
    interface Factory {
        fun create(
            @Provides database: MoneyManagerDatabaseWrapper,
        ): DatabaseComponent
    }
}
