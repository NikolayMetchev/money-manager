package com.moneymanager.ui

import com.moneymanager.database.DatabaseMaintenanceService
import com.moneymanager.database.sql.TransferSourceQueries
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.AttributeTypeRepository
import com.moneymanager.domain.repository.AuditRepository
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.domain.repository.CsvImportRepository
import com.moneymanager.domain.repository.CsvImportStrategyRepository
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.domain.repository.DeviceRepository
import com.moneymanager.domain.repository.TransactionRepository
import com.moneymanager.domain.repository.TransferAttributeRepository
import com.moneymanager.domain.repository.TransferSourceRepository

/**
 * Callback interface for receiving repositories after database is opened.
 */
interface RepositoryCallback {
    fun onRepositoriesReady(
        accountRepository: AccountRepository,
        attributeTypeRepository: AttributeTypeRepository,
        auditRepository: AuditRepository,
        categoryRepository: CategoryRepository,
        csvImportRepository: CsvImportRepository,
        csvImportStrategyRepository: CsvImportStrategyRepository,
        currencyRepository: CurrencyRepository,
        deviceRepository: DeviceRepository,
        maintenanceService: DatabaseMaintenanceService,
        transactionRepository: TransactionRepository,
        transferAttributeRepository: TransferAttributeRepository,
        transferSourceRepository: TransferSourceRepository,
        transferSourceQueries: TransferSourceQueries,
        deviceId: DeviceId,
    )
}
