package com.moneymanager.di.database

import com.moneymanager.database.AccountsGraph
import com.moneymanager.database.ApplicationGraph
import com.moneymanager.database.AuditGraph
import com.moneymanager.database.ImportsGraph
import com.moneymanager.database.PeopleGraph
import com.moneymanager.database.SettingsGraph
import com.moneymanager.database.TransactionsGraph
import com.moneymanager.database.port.DbEntitySource
import com.moneymanager.database.port.DbCsvStrategyImportExport
import com.moneymanager.database.port.DbMaintenance
import com.moneymanager.database.port.DbSampleEntitySource
import com.moneymanager.database.port.DbTransferSource

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
                CsvStrategyImportExport = DbCsvStrategyImportExport(csvStrategyExportService),
                Maintenance = DbMaintenance(maintenanceService),
            ),
        transactions =
            TransactionsGraph(
                transactionRepository = transactionRepository,
                transferSourceRepository = transferSourceRepository,
                attributeTypeRepository = attributeTypeRepository,
                EntitySource = DbEntitySource(entitySourceQueries, deviceId),
                TransferSource = DbTransferSource(transferSourceQueries, deviceId),
                sampleEntitySourcePort = DbSampleEntitySource(entitySourceQueries, deviceId),
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
