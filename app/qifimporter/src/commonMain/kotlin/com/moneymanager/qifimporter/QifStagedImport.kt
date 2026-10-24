package com.moneymanager.qifimporter

import com.moneymanager.csvimporter.effectiveSourceFor
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.qif.QifImport
import com.moneymanager.domain.repository.AccountMappingReadRepository
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.QifImportReadRepository
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.tasks.Progress
import kotlinx.coroutines.flow.first

/**
 * Runs [strategy] over the [qifImport] records that are not yet imported or errored, the QIF
 * counterpart of `applyStagedCsv`. Returns null when there are none. All of a split record's rows share
 * the record's status, so a split record is never half re-run.
 */
@Suppress("LongParameterList")
internal suspend fun applyStagedQif(
    qifImport: QifImport,
    strategy: CsvImportStrategy,
    sourceAccountOverride: AccountId?,
    currencies: List<Currency>,
    accountMappingRepository: AccountMappingReadRepository,
    accountRepository: AccountReadRepository,
    qifImportRepository: QifImportReadRepository,
    maintenance: Maintenance,
    importEngine: ImportEngine,
    onProgress: (suspend (Progress) -> Unit)? = null,
    engineBatchSize: Int = Int.MAX_VALUE,
): QifImportResult? {
    val recordCount = qifImportRepository.countRecords(qifImport.id)
    val records = qifImportRepository.getImportRecords(qifImport.id, recordCount.coerceAtLeast(1), 0)
    val rows =
        QifCsvAdapter
            .toRows(records)
            .filter { it.importStatus == null || it.importStatus == ImportStatus.ERROR }
    if (rows.isEmpty()) return null

    val effectiveSource = effectiveSourceFor(strategy, sourceAccountOverride)
    val accounts = accountRepository.getAllAccounts().first()
    val mappings = accountMappingRepository.getAllMappings().first()
    val historicalAccountNames = accountRepository.getPreviousAccountNames()
    val basePrep =
        buildMapper(strategy, accounts, currencies, mappings, effectiveSource, historicalAccountNames).prepareImport(rows)

    return runImport(
        qifImport = qifImport,
        rows = rows,
        strategy = strategy,
        basePrep = basePrep,
        selectedExistingAccounts = emptyMap(),
        selectedNewAccountNames = emptyMap(),
        selectedSourceAccountId = effectiveSource,
        currencies = currencies,
        accountMappingRepository = accountMappingRepository,
        accountRepository = accountRepository,
        maintenance = maintenance,
        importEngine = importEngine,
        refreshViews = false,
        onProgress = onProgress,
        engineBatchSize = engineBatchSize,
    )
}
