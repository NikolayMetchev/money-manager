package com.moneymanager.csvimporter

import co.touchlab.kermit.Logger
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.passthrough.PassThroughAccount
import com.moneymanager.domain.repository.AccountMappingReadRepository
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CryptoReadRepository
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.domain.repository.TradeReadRepository
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.tasks.Progress
import com.moneymanager.tasks.WeightedProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

private val logger = Logger.withTag("BulkApply")

/**
 * Applies the matching strategy to every [imports] file. Each file's strategy is auto-matched from its
 * column headers, so files from different banks each get the right strategy; files with no match are
 * skipped and counted. Payee/counterparty accounts auto-create with their detected names (no per-file
 * confirmation). For files whose strategy needs a user-chosen source (no SOURCE_ACCOUNT mapping), the
 * source account is [directoryAccounts]'s entry for that file if present (the import directory it was
 * scanned from, or that directory's parent), else the shared [sourceAccountOverride]; hard-coded and
 * per-row strategies resolve their own regardless. Refreshes materialized views once at the end unless
 * [refreshViews] is false (a caller batching several runs refreshes once itself). Reports row-weighted
 * run-wide progress via [onProgress], naming each file by [fileLabel]. [stopRequested] is asked before each file:
 * once it says yes, no more files start (the one under way finishes) and the rest are counted in
 * [BulkApplyResult.filesNotStarted].
 */
@Suppress("LongParameterList")
suspend fun bulkApplyCsv(
    imports: List<CsvImport>,
    sourceAccountOverride: AccountId?,
    strategies: List<CsvImportStrategy>,
    currencies: List<Currency>,
    accountMappingRepository: AccountMappingReadRepository,
    accountRepository: AccountReadRepository,
    csvImportRepository: CsvImportReadRepository,
    maintenance: Maintenance,
    importEngine: ImportEngine,
    onProgress: suspend (Progress) -> Unit,
    passThroughAccounts: List<PassThroughAccount> = emptyList(),
    cryptoRepository: CryptoReadRepository? = null,
    attributeAccountMatchers: Map<String, AttributeAccountMatcher> = emptyMap(),
    directoryAccounts: Map<CsvImportId, AccountId> = emptyMap(),
    tradeRepository: TradeReadRepository? = null,
    refreshViews: Boolean = true,
    fileLabel: (CsvImport) -> String = { it.originalFileName },
    stopRequested: () -> Boolean = { false },
): BulkApplyResult {
    val outcomes = mutableListOf<BulkFileOutcome>()
    // Each imported file's result by its outcome's index, to fold once the match sync has run.
    val results = mutableMapOf<Int, CsvImportResult>()
    var filesNotStarted = 0

    // Preparing a file reads every one of its rows (a PDF's lines, until they're assembled), so they weigh it.
    val tracker =
        WeightedProgress(
            weights = imports.map { it.rowCount },
            preparation = imports.map { it.rowCount },
            preparationNoun = "files",
            onProgress = onProgress,
        )
    tracker.started(detail = "Preparing")

    // Stage every file and create every crypto asset the batch needs up front, sized to the batch-wide max precision
    // per ticker, so cross-file ordering never leaves a ticker missing or a scale factor too small.
    val scans =
        prepareImports(imports, strategies, currencies, csvImportRepository, importEngine, cryptoRepository) { index, file ->
            tracker.prepared(index, "Prepared", fileLabel(file))
        }

    imports.forEachIndexed { index, listedImport ->
        // Once stopped, every remaining file is counted rather than started.
        if (filesNotStarted > 0 || stopRequested()) {
            filesNotStarted++
            return@forEachIndexed
        }
        val label = fileLabel(listedImport)
        tracker.itemStarted(index, label)
        // getAllImports() doesn't populate columns, so re-fetch the full import (which loads them)
        // for the strategy match below.
        val csvImport = csvImportRepository.getImport(listedImport.id).first() ?: listedImport
        val matched = strategies.selectForImport(csvImport, csvImportRepository)

        fun outcome(
            status: BulkFileStatus,
            result: CsvImportResult? = null,
            error: String? = null,
        ) = outcomes.add(
            BulkFileOutcome(
                fileId = csvImport.id.toString(),
                fileName = csvImport.originalFileName,
                status = status,
                strategyName = matched?.name,
                transfersCreated = result?.successCount ?: 0,
                duplicatesSkipped = result?.duplicateCount ?: 0,
                error = error,
            ),
        )
        if (matched == null) {
            outcome(BulkFileStatus.SKIPPED_NO_STRATEGY)
            return@forEachIndexed
        }
        try {
            val result =
                applyStagedCsv(
                    csvImport = csvImport,
                    strategy = matched,
                    sourceAccountOverride = directoryAccounts[csvImport.id] ?: sourceAccountOverride,
                    currencies = currencies,
                    accountMappingRepository = accountMappingRepository,
                    accountRepository = accountRepository,
                    csvImportRepository = csvImportRepository,
                    maintenance = maintenance,
                    importEngine = importEngine,
                    refreshViews = false,
                    passThroughAccounts = passThroughAccounts,
                    onProgress = { tracker.item(index, label, it) },
                    engineBatchSize = BULK_ENGINE_BATCH_SIZE,
                    cryptoRepository = cryptoRepository,
                    attributeAccountMatchers = attributeAccountMatchers,
                    tradeRepository = tradeRepository,
                    syncMatches = false,
                    preparedAssetCodes =
                        scans[csvImport.id]
                            ?.takeIf { it.strategy == matched && it.staged.hasSameRowsAs(csvImport) }
                            ?.codes,
                )
            if (result == null) {
                outcome(BulkFileStatus.NOTHING_TO_IMPORT)
            } else {
                results[outcomes.size] = result
                outcome(BulkFileStatus.IMPORTED, result)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (expected: Exception) {
            logger.e(expected) { "Bulk CSV import failed for ${csvImport.originalFileName}: ${expected.message}" }
            outcome(BulkFileStatus.FAILED, error = expected.message ?: expected::class.simpleName)
        }
    }

    if (results.isNotEmpty()) {
        val gone = importEngine.import(ImportBatch(syncMatches = true)).matchSync.deleted
        results.forEach { (index, result) ->
            val folded = result.foldedInto(gone)
            outcomes[index] = outcomes[index].copy(transfersCreated = folded.successCount, duplicatesSkipped = folded.duplicateCount)
        }
    }
    tracker.done(imports.size - filesNotStarted)
    if (refreshViews) maintenance.refreshMaterializedViews()

    return BulkApplyResult(outcomes, filesNotStarted)
}
