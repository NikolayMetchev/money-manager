package com.moneymanager.di.database

import com.moneymanager.database.AccountsGraph
import com.moneymanager.database.ApplicationGraph
import com.moneymanager.database.AuditGraph
import com.moneymanager.database.ImportsGraph
import com.moneymanager.database.PeopleGraph
import com.moneymanager.database.SettingsGraph
import com.moneymanager.database.TransactionsGraph
import com.moneymanager.database.port.DbEntitySourcePort
import com.moneymanager.database.port.DbCsvStrategyImportExportPort
import com.moneymanager.database.port.DbMaintenancePort
import com.moneymanager.database.port.DbSampleEntitySourcePort
import com.moneymanager.database.port.DbTransferSourcePort

fun DatabaseComponent.toApplicationGraph() =
    ApplicationGraph(
        accounts =
            AccountsGraph(
                accountRepository = accountRepository,
                accountAttributeRepository = accountAttributeRepository,
                categoryRepository = categoryRepository,
                currencyRepository = currencyRepository,
            ),
        imports =
            ImportsGraph(
                apiImportStrategyRepository = apiImportStrategyRepository,
                apiSessionRepository = apiSessionRepository,
                csvAccountMappingRepository = csvAccountMappingRepository,
                csvImportRepository = csvImportRepository,
                csvImportStrategyRepository = csvImportStrategyRepository,
                csvStrategyExportService = csvStrategyExportService,
                csvStrategyImportExportPort = DbCsvStrategyImportExportPort(csvStrategyExportService),
                maintenancePort = DbMaintenancePort(maintenanceService),
            ),
        transactions =
            TransactionsGraph(
                transactionRepository = transactionRepository,
                transferSourceRepository = transferSourceRepository,
                attributeTypeRepository = attributeTypeRepository,
                entitySourcePort = DbEntitySourcePort(entitySourceQueries, deviceId),
                transferSourcePort = DbTransferSourcePort(transferSourceQueries, deviceId),
                sampleEntitySourcePort = DbSampleEntitySourcePort(entitySourceQueries, deviceId),
            ),
        people =
            PeopleGraph(
                personRepository = personRepository,
                personAccountOwnershipRepository = personAccountOwnershipRepository,
                personAttributeRepository = personAttributeRepository,
            ),
        settings =
            SettingsGraph(
                settingsRepository = settingsRepository,
                deviceRepository = deviceRepository,
            ),
        audit = AuditGraph(auditRepository = auditRepository),
        deviceId = deviceId,
    )
