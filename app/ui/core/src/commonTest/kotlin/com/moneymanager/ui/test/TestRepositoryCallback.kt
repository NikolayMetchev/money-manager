package com.moneymanager.ui.test

import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.di.database.DatabaseComponent
import com.moneymanager.ui.RepositoryCallback

/**
 * Creates a repository callback function for use in tests.
 * This bridges MoneyManagerApp's createRepositories parameter with DatabaseComponent.
 */
val testCreateRepositories: (MoneyManagerDatabaseWrapper, RepositoryCallback) -> Unit = { database, callback ->
    val component = DatabaseComponent.create(database)
    callback.onRepositoriesReady(
        accountRepository = component.accountRepository,
        attributeTypeRepository = component.attributeTypeRepository,
        auditRepository = component.auditRepository,
        categoryRepository = component.categoryRepository,
        csvImportRepository = component.csvImportRepository,
        csvImportStrategyRepository = component.csvImportStrategyRepository,
        currencyRepository = component.currencyRepository,
        deviceRepository = component.deviceRepository,
        maintenanceService = component.maintenanceService,
        transactionRepository = component.transactionRepository,
        transferAttributeRepository = component.transferAttributeRepository,
        transferSourceRepository = component.transferSourceRepository,
        transferSourceQueries = component.transferSourceQueries,
        deviceId = component.deviceId,
    )
}
