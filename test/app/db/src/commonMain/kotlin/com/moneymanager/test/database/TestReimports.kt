package com.moneymanager.test.database

import com.moneymanager.csvimporter.BulkReimportResult
import com.moneymanager.csvimporter.ReimportAllPlan
import com.moneymanager.csvimporter.Reimports
import com.moneymanager.csvimporter.source
import com.moneymanager.database.di.DatabaseComponent
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.importengineapi.RerunReads
import kotlinx.coroutines.flow.first

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

/**
 * Plans re-importing the staged file [id] as the re-import dialog does ([Reimports.planAll]), with [strategy], when
 * given, in place of the one the file would be re-imported with.
 */
suspend fun Reimports.planFile(
    id: CsvImportId,
    strategy: CsvImportStrategy? = null,
): ReimportAllPlan {
    val file = requireNotNull(reads.csvImportRepository.getImport(id).first()) { "No staged file $id" }
    val plan = planAll(listOf(file))
    return if (strategy == null) plan else plan.copy(strategies = plan.strategies + (file.source to strategy))
}

/** Re-imports the staged file [id], and every file that depends on it, as the re-import dialog does. */
suspend fun Reimports.reimportFile(
    id: CsvImportId,
    accountId: AccountId? = null,
    strategy: CsvImportStrategy? = null,
): BulkReimportResult = reimportAll(planFile(id, strategy), accountId, currencyId = null, onProgress = {})

/** Runs [plan] as the re-import dialog does. */
suspend fun Reimports.runPlan(
    plan: ReimportAllPlan,
    accountId: AccountId? = null,
): BulkReimportResult = reimportAll(plan, accountId, currencyId = null, onProgress = {})
