package com.moneymanager.di.database

import com.moneymanager.database.DatabaseMaintenanceService
import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.database.service.CsvStrategyExportService
import com.moneymanager.database.sql.EntitySourceQueries
import com.moneymanager.database.sql.TransferSourceQueries
import com.moneymanager.di.DatabaseScope
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.AttributeTypeRepository
import com.moneymanager.domain.repository.AuditRepository
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.domain.repository.CsvAccountMappingRepository
import com.moneymanager.domain.repository.CsvImportRepository
import com.moneymanager.domain.repository.CsvImportStrategyRepository
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.domain.repository.DeviceRepository
import com.moneymanager.domain.repository.PersonAccountOwnershipRepository
import com.moneymanager.domain.repository.PersonRepository
import com.moneymanager.domain.repository.TransactionRepository
import com.moneymanager.domain.repository.TransferAttributeRepository
import com.moneymanager.domain.repository.TransferSourceRepository
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides

/**
 * DI component for database-dependent dependencies.
 * Created after a database is opened, providing repositories and device information.
 */
@DependencyGraph(DatabaseScope::class)
interface DatabaseComponent {
    val accountRepository: AccountRepository
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
    val personRepository: PersonRepository
    val transactionRepository: TransactionRepository
    val transferAttributeRepository: TransferAttributeRepository
    val transferSourceRepository: TransferSourceRepository
    val transferSourceQueries: TransferSourceQueries
    val entitySourceQueries: EntitySourceQueries
    val deviceId: DeviceId

    @DependencyGraph.Factory
    interface Factory {
        fun create(
            @Provides database: MoneyManagerDatabaseWrapper,
        ): DatabaseComponent
    }
}
