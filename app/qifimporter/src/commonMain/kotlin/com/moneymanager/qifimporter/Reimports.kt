package com.moneymanager.qifimporter

import co.touchlab.kermit.Logger
import com.moneymanager.csvimporter.AttributeAccountMatcher
import com.moneymanager.csvimporter.BULK_ENGINE_BATCH_SIZE
import com.moneymanager.csvimporter.BulkReimportFileOutcome
import com.moneymanager.csvimporter.BulkReimportResult
import com.moneymanager.csvimporter.CsvImportResult
import com.moneymanager.csvimporter.STRATEGY_CONTENT_SAMPLE_SIZE
import com.moneymanager.csvimporter.applyStagedCsv
import com.moneymanager.csvimporter.ensureCryptoAssetsForImports
import com.moneymanager.csvimporter.needsSourceAccountOverride
import com.moneymanager.csvimporter.runBulkReimport
import com.moneymanager.csvimporter.selectForCsv
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.qif.QifImport
import com.moneymanager.domain.repository.AccountAttributeReadRepository
import com.moneymanager.domain.repository.AccountMappingReadRepository
import com.moneymanager.domain.repository.CryptoReadRepository
import com.moneymanager.domain.repository.CsvImportStrategyReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.domain.repository.PassThroughAccountReadRepository
import com.moneymanager.importengineapi.AffectedRecords
import com.moneymanager.importengineapi.AffectedRecordsRerunner
import com.moneymanager.importengineapi.CsvImportMutation
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ManualEdit
import com.moneymanager.importengineapi.QifImportMutation
import com.moneymanager.importengineapi.RerunReads
import com.moneymanager.importengineapi.SourceRerunPlan
import com.moneymanager.importengineapi.SourceRerunResult
import com.moneymanager.importengineapi.executeSourceRerun
import com.moneymanager.importengineapi.manualEditsOf
import com.moneymanager.importengineapi.planSourceRerun
import com.moneymanager.tasks.Progress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

private val logger = Logger.withTag("Reimports")

/** A staged CSV or QIF file. Re-import treats both alike: QIF records are run as CSV rows (`QifCsvAdapter`). */
sealed interface StagedFile {
    val source: Source
    val name: String
    val recordCount: Int

    data class Csv(
        val import: CsvImport,
    ) : StagedFile {
        override val source get() = Source.Csv(import.id)
        override val name get() = import.originalFileName
        override val recordCount get() = import.rowCount
    }

    data class Qif(
        val import: QifImport,
    ) : StagedFile {
        override val source get() = Source.Qif(import.id)
        override val name get() = import.originalFileName
        override val recordCount get() = import.recordCount
    }
}

/**
 * The account and currency a file was imported with, read from the transactions it created, so a
 * re-import doesn't ask again: [accountId] is the one account every one of them moves money in or out
 * of (null when none or several do), [currencyId] their most common currency.
 */
data class ImportedSettings(
    val accountId: AccountId?,
    val currencyId: CurrencyId?,
)

/** A strategy reads its currency from the record only when it maps one; otherwise the import supplies it. */
fun CsvImportStrategy.needsCurrencyOverride(): Boolean = config.fieldMappings[TransferField.CURRENCY] == null

/** What re-importing one [file] will do; [needsAccount] and [needsCurrency] say what the person must pick. */
data class FileReimportPlan(
    val file: StagedFile,
    /** The strategy it was last imported with, else the one its content selects; null when neither exists. */
    val strategy: CsvImportStrategy?,
    val imported: ImportedSettings,
    val rerun: SourceRerunPlan,
) {
    val needsAccount: Boolean get() = strategy?.needsSourceAccountOverride() == true && imported.accountId == null
    val needsCurrency: Boolean get() = strategy?.needsCurrencyOverride() == true && imported.currencyId == null
}

/** What a "re-import all" over [files] will do. */
data class ReimportAllPlan(
    val files: List<StagedFile>,
    val strategies: Map<Source, CsvImportStrategy?>,
    val imported: Map<Source, ImportedSettings>,
    /** Every hand edit the run will put back. */
    val manualEdits: List<ManualEdit>,
) {
    val skippedNoStrategy: Int get() = strategies.count { it.value == null }

    /** Whether some file needs the shared account: its strategy has none and it created nothing to read one from. */
    val needsAccount: Boolean
        get() = files.any { strategies[it.source]?.needsSourceAccountOverride() == true && imported[it.source]?.accountId == null }

    val needsCurrency: Boolean
        get() = files.any { strategies[it.source]?.needsCurrencyOverride() == true && imported[it.source]?.currencyId == null }
}

/** How a file's staged records ran: the CSV and QIF import results in one shape. */
data class StagedRunResult(
    val successCount: Int,
    val duplicateCount: Int,
    val failedRows: List<CsvImportResult.FailedRow> = emptyList(),
)

/**
 * The one way to re-import a CSV or QIF file, one at a time or all at once: what the file created is
 * deleted and its records run through the normal import again, with the strategy, account and currency
 * it was imported with ([executeSourceRerun]). Also the [AffectedRecordsRerunner] every re-import (API
 * sessions included) hands the other files' records to that were matched against a deleted transaction.
 */
@Suppress("LongParameterList", "TooManyFunctions")
class Reimports(
    val reads: RerunReads,
    private val csvImportStrategyRepository: CsvImportStrategyReadRepository,
    private val accountMappingRepository: AccountMappingReadRepository,
    private val accountAttributeRepository: AccountAttributeReadRepository,
    private val currencyRepository: CurrencyReadRepository,
    private val cryptoRepository: CryptoReadRepository,
    private val passThroughAccountRepository: PassThroughAccountReadRepository,
    private val maintenance: Maintenance,
    private val importEngine: ImportEngine,
) : AffectedRecordsRerunner {
    /** Plans re-importing [file]: what it created, the other files matched against that, and its hand edits. */
    suspend fun plan(file: StagedFile): FileReimportPlan {
        val loaded = file.reloaded()
        return FileReimportPlan(
            file = loaded,
            strategy = strategyFor(loaded, csvImportStrategyRepository.getAllStrategies().first()),
            imported = importedSettings(loaded.source),
            rerun = rerunPlan(loaded),
        )
    }

    /**
     * Re-imports per [plan]. [accountId] and [currencyId] are used only where the file's own
     * transactions don't say ([FileReimportPlan.needsAccount], [FileReimportPlan.needsCurrency]).
     */
    suspend fun reimport(
        plan: FileReimportPlan,
        accountId: AccountId?,
        currencyId: CurrencyId?,
        onProgress: (suspend (Progress) -> Unit)? = null,
        refreshViews: Boolean = true,
    ): SourceRerunResult<StagedRunResult?> {
        val strategy = requireNotNull(plan.strategy) { "No strategy matches ${plan.file.name}" }
        val settings =
            ImportedSettings(plan.imported.accountId ?: accountId, plan.imported.currencyId ?: currencyId)
        return importEngine.executeSourceRerun(
            plan = plan.rerun,
            resetRecords = resetBatch(plan.file),
            reads = reads,
            maintenance = maintenance,
            rerunAffected = this,
            onProgress = onProgress,
            refreshViews = refreshViews,
        ) {
            runStaged(plan.file, strategy, settings, currencies(), onProgress)
        }
    }

    /** Plans a "re-import all" over [files], reading each file's settings before any of them is deleted. */
    suspend fun planAll(files: List<StagedFile>): ReimportAllPlan {
        val strategies = csvImportStrategyRepository.getAllStrategies().first()
        val loaded = files.map { it.reloaded() }
        return ReimportAllPlan(
            files = loaded,
            strategies = loaded.associate { it.source to strategyFor(it, strategies) },
            imported = loaded.associate { it.source to importedSettings(it.source) },
            manualEdits = loaded.flatMap { manualEditsOf(it.source, reads) },
        )
    }

    /**
     * Re-imports every file of [plan], one after another, refreshing materialized views once at the end.
     * [accountId] and [currencyId] are for the files whose own transactions don't say.
     */
    suspend fun reimportAll(
        plan: ReimportAllPlan,
        accountId: AccountId?,
        currencyId: CurrencyId?,
        onProgress: suspend (Progress) -> Unit,
    ): BulkReimportResult =
        runBulkReimport(
            imports = plan.files,
            rowCount = { it.recordCount },
            fileName = { it.name },
            logLabel = "file",
            maintenance = maintenance,
            onProgress = onProgress,
            startDetail = "Preparing",
            prepare = {
                // Every crypto asset the run needs exists before the first file, at the run-wide precision.
                ensureCryptoAssetsForImports(
                    plan.files.filterIsInstance<StagedFile.Csv>().map { it.import },
                    csvImportStrategyRepository.getAllStrategies().first(),
                    currencies(),
                    reads.csvImportRepository,
                    importEngine,
                    cryptoRepository,
                )
            },
        ) { _, file, _, onFileProgress ->
            val strategy = plan.strategies[file.source]
            when {
                // An empty file has nothing to re-import, and (unlike a missing strategy) is not counted.
                file.recordCount == 0 -> {
                    BulkReimportFileOutcome.NoWork
                }

                strategy == null -> {
                    BulkReimportFileOutcome.NoStrategy
                }

                else -> {
                    val filePlan =
                        FileReimportPlan(file, strategy, plan.imported.getValue(file.source), rerunPlan(file))
                    val result = reimport(filePlan, accountId, currencyId, onFileProgress, refreshViews = false)
                    BulkReimportFileOutcome.Reimported(
                        result = result,
                        successCount = result.rerun?.successCount ?: 0,
                        duplicateCount = result.rerun?.duplicateCount ?: 0,
                    )
                }
            }
        }

    /** Re-runs the reset records of the files in [affected], each as it was imported. */
    override suspend fun rerun(
        affected: AffectedRecords,
        onProgress: (suspend (Progress) -> Unit)?,
    ) {
        val files =
            affected.csvRows.keys.mapNotNull { id ->
                reads.csvImportRepository
                    .getImport(id)
                    .first()
                    ?.let(StagedFile::Csv)
            } +
                affected.qifRecords.keys.mapNotNull { id ->
                    reads.qifImportRepository
                        .getImport(id)
                        .first()
                        ?.let(StagedFile::Qif)
                }
        if (files.isEmpty()) return
        val strategies = csvImportStrategyRepository.getAllStrategies().first()
        val currencies = currencies()
        files.forEach { file ->
            // One file failing leaves its reset records to its next import rather than failing the
            // re-import that reset them, whose own work is already done.
            try {
                val strategy = strategyFor(file, strategies) ?: return@forEach
                runStaged(file, strategy, importedSettings(file.source), currencies, onProgress)
            } catch (expected: CancellationException) {
                throw expected
            } catch (expected: Exception) {
                logger.e(expected) { "Re-running the records of ${file.name} matched against a re-imported transaction failed" }
            }
        }
    }

    /**
     * Reads the account and currency [source] was imported with; see [ImportedSettings]. Rows that matched
     * another file's transaction as duplicates count too, so a file that created nothing still knows them.
     */
    suspend fun importedSettings(source: Source): ImportedSettings {
        val referenced =
            when (source) {
                is Source.Csv -> {
                    reads.csvImportRepository.getImportRows(source.importId, limit = Int.MAX_VALUE, offset = 0).mapNotNull { it.transferId }
                }

                is Source.Qif -> {
                    reads.qifImportRepository
                        .getImportRecords(
                            source.importId,
                            limit = Int.MAX_VALUE,
                            offset = 0,
                        ).mapNotNull { it.transferId }
                }

                else -> {
                    emptyList()
                }
            }
        val transfers =
            reads.transactionRepository
                .getTransactionsByIds(reads.transferSourceRepository.getTransfersCreatedBy(source).keys + referenced)
                .values
        if (transfers.isEmpty()) return ImportedSettings(null, null)
        val sharedAccount =
            transfers
                .map { setOf(it.sourceAccountId, it.targetAccountId) }
                .reduce { shared, accounts -> shared intersect accounts }
                .singleOrNull()
        val currency =
            transfers
                .mapNotNull { it.amount.asset as? Currency }
                .groupingBy { it.id }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key
        return ImportedSettings(sharedAccount, currency)
    }

    private suspend fun rerunPlan(file: StagedFile): SourceRerunPlan =
        when (file) {
            is StagedFile.Csv -> {
                planSourceRerun(
                    source = file.source,
                    tradeIds = reads.csvImportRepository.getTradeIdsCreatedByImport(file.import.id),
                    createdAccountIds = reads.csvImportRepository.getAccountsCreatedByImport(file.import.id),
                    reads = reads,
                )
            }

            is StagedFile.Qif -> {
                planSourceRerun(
                    source = file.source,
                    // QIF import never creates trades.
                    tradeIds = emptySet(),
                    createdAccountIds = reads.qifImportRepository.getAccountsCreatedByImport(file.import.id),
                    reads = reads,
                )
            }
        }

    // Returns every record to never-imported, keeping the file's import history.
    private fun resetBatch(file: StagedFile): ImportBatch =
        when (file) {
            is StagedFile.Csv -> ImportBatch(csvImportMutations = listOf(CsvImportMutation.ResetRowStatuses(file.import.id, null)))
            is StagedFile.Qif -> ImportBatch(qifImportMutations = listOf(QifImportMutation.ResetRecordStatuses(file.import.id, null)))
        }

    // A listed import may lack what strategy selection reads (getAllImports() doesn't populate CSV columns).
    private suspend fun StagedFile.reloaded(): StagedFile =
        when (this) {
            is StagedFile.Csv -> {
                reads.csvImportRepository
                    .getImport(import.id)
                    .first()
                    ?.let(StagedFile::Csv) ?: this
            }

            is StagedFile.Qif -> {
                reads.qifImportRepository
                    .getImport(import.id)
                    .first()
                    ?.let(StagedFile::Qif) ?: this
            }
        }

    private suspend fun strategyFor(
        file: StagedFile,
        strategies: List<CsvImportStrategy>,
    ): CsvImportStrategy? =
        when (file) {
            is StagedFile.Csv -> {
                file.import.lastAppliedStrategyId?.let { id -> strategies.find { it.id == id } }
                    ?: strategies.selectForCsv(
                        file.import.originalFileName,
                        file.import.columns,
                        reads.csvImportRepository.getImportRows(file.import.id, limit = STRATEGY_CONTENT_SAMPLE_SIZE, offset = 0),
                    )
            }

            is StagedFile.Qif -> {
                val qifStrategies = strategies.qifCompatible()
                file.import.lastAppliedStrategyId?.let { id -> qifStrategies.find { it.id == id } }
                    ?: qifStrategies.selectForQifContent(
                        QifCsvAdapter.toRows(
                            reads.qifImportRepository.getImportRecords(file.import.id, STRATEGY_CONTENT_SAMPLE_SIZE, 0),
                        ),
                        QifCsvAdapter.columns,
                    )
            }
        }

    // Runs [strategy] over the file's records that are not yet imported or errored.
    private suspend fun runStaged(
        file: StagedFile,
        strategy: CsvImportStrategy,
        settings: ImportedSettings,
        currencies: List<Currency>,
        onProgress: (suspend (Progress) -> Unit)?,
    ): StagedRunResult? {
        val effective = strategy.withQifCurrency(settings.currencyId.takeIf { strategy.needsCurrencyOverride() })
        return when (file) {
            is StagedFile.Csv -> {
                applyStagedCsv(
                    csvImport = file.import,
                    strategy = effective,
                    sourceAccountOverride = settings.accountId,
                    currencies = currencies,
                    accountMappingRepository = accountMappingRepository,
                    accountRepository = reads.accountRepository,
                    csvImportRepository = reads.csvImportRepository,
                    maintenance = maintenance,
                    importEngine = importEngine,
                    refreshViews = false,
                    passThroughAccounts = passThroughAccountRepository.getAll().first(),
                    onProgress = onProgress,
                    engineBatchSize = BULK_ENGINE_BATCH_SIZE,
                    cryptoRepository = cryptoRepository,
                    attributeAccountMatchers = AttributeAccountMatcher.registry(accountAttributeRepository.getAll().first()),
                    tradeRepository = reads.tradeRepository,
                )?.let { StagedRunResult(it.successCount, it.duplicateCount, it.failedRows) }
            }

            is StagedFile.Qif -> {
                applyStagedQif(
                    qifImport = file.import,
                    strategy = effective,
                    sourceAccountOverride = settings.accountId,
                    currencies = currencies,
                    accountMappingRepository = accountMappingRepository,
                    accountRepository = reads.accountRepository,
                    qifImportRepository = reads.qifImportRepository,
                    maintenance = maintenance,
                    importEngine = importEngine,
                    onProgress = onProgress,
                    engineBatchSize = BULK_ENGINE_BATCH_SIZE,
                )?.let { StagedRunResult(it.successCount, it.duplicateCount) }
            }
        }
    }

    private suspend fun currencies(): List<Currency> = currencyRepository.getAllCurrencies().first()
}
