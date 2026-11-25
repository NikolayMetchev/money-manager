package com.moneymanager.csvimporter

import co.touchlab.kermit.Logger
import com.moneymanager.coroutines.runCatchingCancellable
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.model.CryptoRegistry
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.accountmapping.AccountMapping
import com.moneymanager.domain.model.codesByName
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedCurrencyMapping
import com.moneymanager.domain.model.csvstrategy.LegAssembly
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.csvstrategy.ownedAttributeValues
import com.moneymanager.domain.model.csvstrategy.uniqueIdentifierAttributeTypes
import com.moneymanager.domain.model.observation.ImportRecordKey
import com.moneymanager.domain.model.passthrough.PassThroughAccount
import com.moneymanager.domain.model.resolveAssetCode
import com.moneymanager.domain.model.rules.ColumnRecord
import com.moneymanager.domain.model.rules.EXCLUDED_ATTRIBUTE_TYPE_NAME
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.RuleEngine
import com.moneymanager.domain.model.rules.RuleEvaluator
import com.moneymanager.domain.repository.AccountMappingReadRepository
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CryptoReadRepository
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.domain.repository.TradeReadRepository
import com.moneymanager.domain.repository.assetAliasMap
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.BatchRelationship
import com.moneymanager.importengineapi.CsvImportMutation
import com.moneymanager.importengineapi.ExistingUniqueKeyExtractor
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportFee
import com.moneymanager.importengineapi.ImportOwnershipIntent
import com.moneymanager.importengineapi.ImportPassThrough
import com.moneymanager.importengineapi.ImportPersonIntent
import com.moneymanager.importengineapi.ImportReportedBalance
import com.moneymanager.importengineapi.ImportRowKey
import com.moneymanager.importengineapi.ImportTradeIntent
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.LocalPersonKey
import com.moneymanager.importengineapi.LocalTradeKey
import com.moneymanager.importengineapi.ObservationOwnerKey
import com.moneymanager.importengineapi.ObservationSlot
import com.moneymanager.importengineapi.PassThroughDetector
import com.moneymanager.importengineapi.PersonMatchKey
import com.moneymanager.importengineapi.applyCsvImportMutations
import com.moneymanager.importengineapi.createAccount
import com.moneymanager.importengineapi.createAccountMapping
import com.moneymanager.importengineapi.createAccountMappings
import com.moneymanager.importengineapi.createAccounts
import com.moneymanager.importengineapi.createCrypto
import com.moneymanager.importengineapi.dedupePolicy
import com.moneymanager.importengineapi.deleteEmptyImportCreatedAccounts
import com.moneymanager.importengineapi.feeAccounts
import com.moneymanager.importengineapi.getOrCreateAttributeTypes
import com.moneymanager.importengineapi.normalizeNameKey
import com.moneymanager.importengineapi.tradeDedupePolicy
import com.moneymanager.tasks.Progress
import com.moneymanager.tasks.WeightedProgress
import com.moneymanager.xlsx.createXlsxParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = Logger.withTag("CsvBulkApply")

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
