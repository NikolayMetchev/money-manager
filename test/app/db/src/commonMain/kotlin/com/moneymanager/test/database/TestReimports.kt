package com.moneymanager.test.database

import com.moneymanager.csvimporter.Reimports
import com.moneymanager.database.di.DatabaseComponent
import com.moneymanager.domain.Maintenance
import com.moneymanager.importengineapi.RerunReads

/** The session's [Reimports], as `ImportDirectoryService` builds it, over this test database. */
fun DatabaseComponent.reimports(maintenance: Maintenance = maintenanceService): Reimports =
    Reimports(
        reads =
            RerunReads(
                transactionRepository = transactionRepository,
                transferSourceRepository = transferSourceRepository,
                relationshipRepository = relationshipRepository,
                auditRepository = auditRepository,
                accountRepository = accountRepository,
                csvImportRepository = csvImportRepository,
                tradeRepository = tradeRepository,
            ),
        csvImportStrategyRepository = csvImportStrategyRepository,
        accountMappingRepository = accountMappingRepository,
        accountAttributeRepository = accountAttributeRepository,
        currencyRepository = currencyRepository,
        cryptoRepository = cryptoRepository,
        passThroughAccountRepository = passThroughAccountRepository,
        maintenance = maintenance,
        importEngine = importEngine,
    )
