package com.moneymanager.di.database

import com.moneymanager.database.Accounts
import com.moneymanager.database.Application
import com.moneymanager.database.Audit
import com.moneymanager.database.Imports
import com.moneymanager.database.People
import com.moneymanager.database.Settings
import com.moneymanager.database.Transactions
import com.moneymanager.database.port.DbCsvStrategyImportExport
import com.moneymanager.database.port.DbEntitySource
import com.moneymanager.database.port.DbMaintenance
import com.moneymanager.database.port.DbSampleEntitySource

fun DatabaseComponent.toApplication() =
    Application(
        accounts =
            Accounts(
                accountRepository = accountRepository,
                accountAttributeRepository = accountAttributeRepository,
                categoryRepository = categoryRepository,
                currencyRepository = currencyRepository,
            ),
        imports =
            Imports(
                apiImportStrategyRepository = apiImportStrategyRepository,
                apiSessionRepository = apiSessionRepository,
                csvAccountMappingRepository = csvAccountMappingRepository,
                csvImportRepository = csvImportRepository,
                csvImportStrategyRepository = csvImportStrategyRepository,
                csvStrategyExportService = csvStrategyExportService,
                CsvStrategyImportExport = DbCsvStrategyImportExport(csvStrategyExportService),
                Maintenance = DbMaintenance(maintenanceService),
            ),
        transactions =
            Transactions(
                transactionRepository = transactionRepository,
                transferSourceRepository = transferSourceRepository,
                attributeTypeRepository = attributeTypeRepository,
                EntitySource = DbEntitySource(entitySourceQueries, transferSourceQueries, deviceId),
                sampleEntitySourcePort = DbSampleEntitySource(entitySourceQueries, transferSourceQueries, deviceId),
            ),
        people =
            People(
                personRepository = personRepository,
                personAccountOwnershipRepository = personAccountOwnershipRepository,
                personAttributeRepository = personAttributeRepository,
            ),
        settings =
            Settings(
                settingsRepository = settingsRepository,
                deviceRepository = deviceRepository,
            ),
        audit = Audit(auditRepository = auditRepository),
        deviceId = deviceId,
    )
