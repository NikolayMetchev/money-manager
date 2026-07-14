@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.csvimporter

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
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.accountmapping.AccountMapping
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.passthrough.PassThroughAccount
import com.moneymanager.domain.repository.AccountMappingReadRepository
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CryptoReadRepository
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.BatchRelationship
import com.moneymanager.importengineapi.CsvImportMutation
import com.moneymanager.importengineapi.DedupePolicy
import com.moneymanager.importengineapi.ExistingUniqueKeyExtractor
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportFee
import com.moneymanager.importengineapi.ImportPassThrough
import com.moneymanager.importengineapi.ImportProgress
import com.moneymanager.importengineapi.ImportRowKey
import com.moneymanager.importengineapi.ImportTradeIntent
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.LocalTradeKey
import com.moneymanager.importengineapi.PassThroughDetector
import com.moneymanager.importengineapi.applyCsvImportMutations
import com.moneymanager.importengineapi.createAccount
import com.moneymanager.importengineapi.createAccountMapping
import com.moneymanager.importengineapi.createAccountMappings
import com.moneymanager.importengineapi.createAccounts
import com.moneymanager.importengineapi.createCrypto
import com.moneymanager.importengineapi.getOrCreateAttributeTypes
import kotlinx.coroutines.flow.first
import org.lighthousegames.logging.logging
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

private val logger = logging()

/**
 * Collapses consecutive duplicate accounts out of a pass-through spend-leg chain ([nodes] = the conduit
 * account ids followed by the merchant account id), keeping [spendDescriptions] aligned 1:1 with the
 * surviving conduit legs by dropping the description of each removed leg.
 *
 * The engine builds one spend leg per adjacent node pair (`nodes[i] -> nodes[i+1]`). A node that equals
 * its predecessor — e.g. a merchant that resolved to its feeding conduit, or a repeated conduit — would
 * make that leg a zero-movement `source == target` transfer, which the `transfer` CHECK constraint
 * rejects (aborting the whole file). Returns the collapsed (nodes, descriptions), or null when fewer
 * than two distinct nodes remain (no real pass-through — the caller imports the plain funding transfer).
 */
internal fun collapsePassThroughChain(
    nodes: List<AccountId>,
    spendDescriptions: List<String>,
): Pair<List<AccountId>, List<String>>? {
    if (nodes.isEmpty()) return null
    val keptNodes = mutableListOf(nodes.first())
    val keptDescriptions = mutableListOf<String>()
    for (legIndex in 0 until nodes.size - 1) {
        if (nodes[legIndex + 1] != keptNodes.last()) {
            keptNodes += nodes[legIndex + 1]
            keptDescriptions += spendDescriptions.getOrElse(legIndex) { "" }
        }
    }
    return if (keptNodes.size < 2) null else keptNodes to keptDescriptions
}

/**
 * Ensures a crypto asset exists for every crypto ticker appearing in [strategy]'s currency-lookup
 * columns across [rows], creating any missing ones via the engine (upsert = idempotent), and returns
 * the full crypto-asset set to feed the mapper. Public so the single-file apply path (ApplyStrategyDialog)
 * can resolve + create crypto the same way the bulk/re-import paths do.
 *
 * **Any** value in a currency-lookup column that is not a known fiat currency is treated as a crypto
 * asset and auto-created, using the catalog's display name (or the ticker itself when unknown), so a
 * crypto ticker resolves instead of failing with "Currency not found". This is unconditional — it does
 * not depend on any per-strategy flag, so it holds even for strategies stored before crypto support.
 * Every asset is created at the fixed 18-decimal crypto scale, so any observed precision parses
 * exactly via `Money.fromDisplayValue` (which throws on excess precision). Returns empty when no
 * [cryptoRepository] is supplied.
 */
suspend fun ensureCryptoAssets(
    strategy: CsvImportStrategy,
    columns: List<CsvColumn>,
    rows: List<CsvRow>,
    currencies: List<Currency>,
    importEngine: ImportEngine,
    cryptoRepository: CryptoReadRepository?,
): List<CryptoAsset> {
    if (cryptoRepository == null) return emptyList()
    val fiatCodes = currencies.mapTo(mutableSetOf()) { it.code.uppercase() }
    val existingCrypto = cryptoRepository.getAllCryptoAssets().first().mapTo(mutableSetOf()) { it.code.uppercase() }
    createCryptoAssets(collectCryptoCodes(strategy, columns, rows, fiatCodes, existingCrypto), importEngine)
    return cryptoRepository.getAllCryptoAssets().first()
}

/**
 * Pre-creates every crypto asset needed across a whole batch of [imports] **before** any file is
 * imported. This is what the per-file [ensureCryptoAssets] cannot do: a fiat file processed before
 * the crypto file that introduces a ticker would fail with "Currency not found". Creating everything
 * up front removes that ordering dependency. Returns the full crypto-asset set. No-op without
 * [cryptoRepository].
 */
suspend fun ensureCryptoAssetsForImports(
    imports: List<CsvImport>,
    strategies: List<CsvImportStrategy>,
    currencies: List<Currency>,
    csvImportRepository: CsvImportReadRepository,
    importEngine: ImportEngine,
    cryptoRepository: CryptoReadRepository?,
): List<CryptoAsset> {
    if (cryptoRepository == null) return emptyList()
    val fiatCodes = currencies.mapTo(mutableSetOf()) { it.code.uppercase() }
    val existingCrypto = cryptoRepository.getAllCryptoAssets().first().mapTo(mutableSetOf()) { it.code.uppercase() }

    val codes = mutableSetOf<String>()
    for (listedImport in imports) {
        codes += cryptoCodesForImport(listedImport, strategies, csvImportRepository, fiatCodes, existingCrypto)
    }
    createCryptoAssets(codes, importEngine)
    return cryptoRepository.getAllCryptoAssets().first()
}

/** Per-import crypto codes for the batch pre-pass: matches the strategy and scans importable rows. */
private suspend fun cryptoCodesForImport(
    listedImport: CsvImport,
    strategies: List<CsvImportStrategy>,
    csvImportRepository: CsvImportReadRepository,
    fiatCodes: Set<String>,
    existingCrypto: Set<String>,
): Set<String> {
    val csvImport = csvImportRepository.getImport(listedImport.id).first() ?: return emptySet()
    val sampleRows = csvImportRepository.getImportRows(csvImport.id, limit = STRATEGY_CONTENT_SAMPLE_SIZE, offset = 0)
    val strategy =
        csvImport.lastAppliedStrategyId?.let { id -> strategies.find { it.id == id } }
            ?: strategies.selectForCsv(csvImport.originalFileName, csvImport.columns, sampleRows)
            ?: return emptySet()
    // Scan ALL rows, not just importable ones: a re-import remaps already-IMPORTED rows too (value
    // updates, transfer→trade conversions), and their tickers must resolve at plan time. For a fresh
    // import every row is unimported, so this is the same set.
    val allRows = csvImportRepository.getImportRows(csvImport.id, limit = csvImport.rowCount.coerceAtLeast(1), offset = 0)
    return collectCryptoCodes(strategy, csvImport.columns, allRows, fiatCodes, existingCrypto)
}

/**
 * Scans [rows] of [strategy]'s currency-lookup columns and returns every non-fiat ticker not already
 * an [existingCryptoCodes] asset. Any non-fiat code qualifies (unconditional — no per-strategy flag).
 * Pure; does not create anything.
 */
private fun collectCryptoCodes(
    strategy: CsvImportStrategy,
    columns: List<CsvColumn>,
    rows: List<CsvRow>,
    fiatCodes: Set<String>,
    existingCryptoCodes: Set<String>,
): Set<String> {
    fun columnIndex(name: String): Int? = columns.firstOrNull { it.originalName == name }?.columnIndex

    val currencyColumns =
        listOf(TransferField.CURRENCY, TransferField.TO_CURRENCY).mapNotNull { field ->
            (strategy.fieldMappings[field] as? CurrencyLookupMapping)?.columnName?.let(::columnIndex)
        }
    if (currencyColumns.isEmpty()) return emptySet()

    val codes = mutableSetOf<String>()
    for (row in rows) {
        for (currencyCol in currencyColumns) {
            val code =
                row.values
                    .getOrNull(currencyCol)
                    ?.trim()
                    ?.uppercase()
            if (!code.isNullOrEmpty() && code !in fiatCodes && code !in existingCryptoCodes) {
                codes += code
            }
        }
    }
    return codes
}

/** Creates a crypto asset for each of [codes] (fixed 18-decimal scale; display name from the catalog). */
private suspend fun createCryptoAssets(
    codes: Set<String>,
    importEngine: ImportEngine,
) {
    for (code in codes) {
        importEngine.createCrypto(code, name = CryptoRegistry.nameFor(code))
    }
}

/** Summary of a bulk CSV import run across many files. */
data class CsvBulkResult(
    override val filesImported: Int,
    override val transfersCreated: Int,
    override val duplicatesSkipped: Int,
    override val filesSkippedNoStrategy: Int,
    override val filesFailed: Int,
) : BulkImportResult

/**
 * Applies the matching strategy to every [imports] file. Each file's strategy is auto-matched from its
 * column headers, so files from different banks each get the right strategy; files with no match are
 * skipped and counted. Payee/counterparty accounts auto-create with their detected names (no per-file
 * confirmation). The shared [sourceAccountOverride] is used only for files whose strategy needs a
 * user-chosen source (no SOURCE_ACCOUNT mapping); hard-coded and per-row strategies resolve their own.
 * Refreshes materialized views once at the end. Reports row-weighted run-wide progress via [onProgress].
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
    onProgress: suspend (BulkImportProgress) -> Unit,
    passThroughAccounts: List<PassThroughAccount> = emptyList(),
    cryptoRepository: CryptoReadRepository? = null,
): CsvBulkResult {
    var filesImported = 0
    var transfers = 0
    var duplicates = 0
    var skippedNoStrategy = 0
    var failed = 0

    val tracker = BulkProgressTracker(imports.map { it.rowCount }, onProgress)
    tracker.started(detail = "Preparing")

    // Create every crypto asset the batch needs up front, sized to the batch-wide max precision per
    // ticker, so cross-file ordering never leaves a ticker missing or a scale factor too small.
    ensureCryptoAssetsForImports(imports, strategies, currencies, csvImportRepository, importEngine, cryptoRepository)

    imports.forEachIndexed { index, listedImport ->
        tracker.fileStarted(index, listedImport.originalFileName)
        // getAllImports() doesn't populate columns, so re-fetch the full import (which loads them)
        // for the strategy match below.
        val csvImport = csvImportRepository.getImport(listedImport.id).first() ?: listedImport
        val sampleRows = csvImportRepository.getImportRows(csvImport.id, limit = STRATEGY_CONTENT_SAMPLE_SIZE, offset = 0)
        val matched = strategies.selectForCsv(csvImport.originalFileName, csvImport.columns, sampleRows)
        if (matched == null) {
            skippedNoStrategy++
            return@forEachIndexed
        }
        try {
            val result =
                applyStagedCsv(
                    csvImport = csvImport,
                    strategy = matched,
                    sourceAccountOverride = sourceAccountOverride,
                    currencies = currencies,
                    accountMappingRepository = accountMappingRepository,
                    accountRepository = accountRepository,
                    csvImportRepository = csvImportRepository,
                    maintenance = maintenance,
                    importEngine = importEngine,
                    refreshViews = false,
                    passThroughAccounts = passThroughAccounts,
                    onProgress = { tracker.phase(index, csvImport.originalFileName, it) },
                    engineBatchSize = BULK_ENGINE_BATCH_SIZE,
                    cryptoRepository = cryptoRepository,
                ) ?: return@forEachIndexed
            filesImported++
            transfers += result.successCount
            duplicates += result.duplicateCount
        } catch (expected: Exception) {
            logger.error(expected) { "Bulk CSV import failed for ${csvImport.originalFileName}: ${expected.message}" }
            failed++
        }
    }

    tracker.done()
    maintenance.refreshMaterializedViews()

    return CsvBulkResult(
        filesImported = filesImported,
        transfersCreated = transfers,
        duplicatesSkipped = duplicates,
        filesSkippedNoStrategy = skippedNoStrategy,
        filesFailed = failed,
    )
}

/**
 * Applies a fixed [strategy] to all importable rows of an already-staged [csvImport] and returns the
 * run result (null when there are no rows left to import). Unlike [bulkApplyCsv] the strategy is given,
 * not auto-matched — used by re-import and by [bulkApplyCsv] itself after it selects one.
 * [refreshViews] = false lets a caller batch many files and refresh once at the end.
 * [onProgress]/[engineBatchSize] are forwarded to the engine so a caller can show per-chunk progress.
 */
@Suppress("LongParameterList")
suspend fun applyStagedCsv(
    csvImport: CsvImport,
    strategy: CsvImportStrategy,
    sourceAccountOverride: AccountId?,
    currencies: List<Currency>,
    accountMappingRepository: AccountMappingReadRepository,
    accountRepository: AccountReadRepository,
    csvImportRepository: CsvImportReadRepository,
    maintenance: Maintenance,
    importEngine: ImportEngine,
    refreshViews: Boolean,
    passThroughAccounts: List<PassThroughAccount> = emptyList(),
    onProgress: (suspend (ImportProgress) -> Unit)? = null,
    engineBatchSize: Int = Int.MAX_VALUE,
    cryptoRepository: CryptoReadRepository? = null,
): CsvImportResult? {
    val allRows = csvImportRepository.getImportRows(csvImport.id, limit = csvImport.rowCount.coerceAtLeast(1), offset = 0)
    val rows = allRows.filter { it.importStatus == null || it.importStatus == ImportStatus.ERROR }
    if (rows.isEmpty()) {
        // A genuinely empty file (a header-only export with no data rows) has nothing to import, but
        // must still be marked applied or it reappears in the Unimported tab on every "Import all".
        // Record the strategy application once (the lastAppliedAt guard keeps repeated runs a no-op).
        // Scoped to allRows.isEmpty() so it never fires on re-import, which always has rows and whose
        // "nothing new to import" outcome must stay a null result.
        if (allRows.isEmpty() && csvImport.lastAppliedAt == null) {
            recordCsvApplication(importEngine, csvImport.id, strategy)
            return CsvImportResult(successCount = 0, failedRows = emptyList())
        }
        return null
    }

    // The shared override only applies to strategies that need a user-chosen source. A hard-coded
    // mapping resolves its own account; a per-row mapping decides per row.
    val effectiveSource = effectiveSourceFor(strategy, sourceAccountOverride)

    // On-demand: create a crypto asset for every non-fiat ticker appearing in the strategy's currency
    // column, so rows denominated in crypto resolve to a real crypto asset (upsert = idempotent on
    // re-import). No-op unless a CryptoReadRepository is supplied and the currency column carries crypto.
    val cryptoAssets = ensureCryptoAssets(strategy, csvImport.columns, rows, currencies, importEngine, cryptoRepository)

    // Re-fetch accounts so payee accounts created by earlier files in the same scan are seen.
    val accounts = accountRepository.getAllAccounts().first()
    val mappings = accountMappingRepository.getAllMappings().first()
    val historicalAccountNames = accountRepository.getPreviousAccountNames()
    val basePrep =
        buildCsvMapper(
            strategy,
            csvImport.columns,
            accounts,
            currencies,
            mappings,
            effectiveSource,
            passThroughAccounts,
            historicalAccountNames,
            cryptoAssets,
        ).prepareImport(rows)

    return runCsvImport(
        cryptoAssets = cryptoAssets,
        csvImport = csvImport,
        rows = rows,
        columns = csvImport.columns,
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
        refreshViews = refreshViews,
        passThroughAccounts = passThroughAccounts,
        onProgress = onProgress,
        engineBatchSize = engineBatchSize,
    )
}

/**
 * Records that [strategy] was applied to [csvImportId], moving the file into the "Imported" tab
 * (`last_applied_at` becomes non-null). Failures are logged, not thrown — the transfers are already
 * committed, so a missing history row must not fail the import.
 */
private suspend fun recordCsvApplication(
    importEngine: ImportEngine,
    csvImportId: CsvImportId,
    strategy: CsvImportStrategy,
) {
    runCatching {
        importEngine.applyCsvImportMutations(
            listOf(
                CsvImportMutation.RecordApplication(
                    id = csvImportId,
                    strategyId = strategy.id,
                    strategyName = strategy.name,
                    appliedAt = Clock.System.now(),
                ),
            ),
        )
    }.onFailure { error ->
        logger.warn { "Import application history could not be recorded for import $csvImportId: ${error.message}" }
    }
}

/**
 * Resolves the source account to use for a file under [strategy]:
 * - a [HardCodedAccountMapping] resolves its own account (the override is ignored),
 * - a per-row SOURCE_ACCOUNT mapping decides per row (null override),
 * - no SOURCE_ACCOUNT mapping falls back to the shared [override].
 */
fun effectiveSourceFor(
    strategy: CsvImportStrategy,
    override: AccountId?,
): AccountId? =
    when (val mapping = strategy.fieldMappings[TransferField.SOURCE_ACCOUNT]) {
        is HardCodedAccountMapping -> mapping.accountId
        null -> override
        else -> null
    }

/** True when [strategy] needs a user-chosen source account (no SOURCE_ACCOUNT mapping of its own). */
fun CsvImportStrategy.needsSourceAccountOverride(): Boolean = fieldMappings[TransferField.SOURCE_ACCOUNT] == null

fun buildCsvMapper(
    strategy: CsvImportStrategy,
    columns: List<CsvColumn>,
    accounts: List<Account>,
    currencies: List<Currency>,
    accountMappings: List<AccountMapping>,
    sourceAccountOverride: AccountId?,
    passThroughAccounts: List<PassThroughAccount> = emptyList(),
    historicalAccountNames: Map<String, AccountId> = emptyMap(),
    cryptoAssets: List<CryptoAsset> = emptyList(),
): CsvTransferMapper =
    CsvTransferMapper(
        strategy = strategy,
        columns = columns,
        existingAccounts = accounts.associateBy { it.name },
        existingCurrencies = currencies.associateBy { it.id },
        existingCurrenciesByCode = currencies.associateBy { it.code.uppercase() },
        existingCryptoByCode = cryptoAssets.associateBy { it.code.uppercase() },
        accountMappings = accountMappings,
        historicalAccountNames = historicalAccountNames,
        sourceAccountOverride = sourceAccountOverride,
        passThroughDetector = passThroughAccounts.takeIf { it.isNotEmpty() }?.let { PassThroughDetector(it) },
    )

/**
 * Saves [mappings] in a single batch, falling back to per-mapping saves if the atomic batch fails so
 * one bad mapping doesn't block the rest.
 */
private suspend fun persistMappingsWithFallback(
    importEngine: ImportEngine,
    mappings: List<AccountMapping>,
) {
    if (mappings.isEmpty()) return
    try {
        importEngine.createAccountMappings(mappings)
        logger.info { "Saved ${mappings.size} account mappings" }
    } catch (expected: Exception) {
        // Batch is atomic, nothing was committed — retry per mapping so one bad mapping doesn't block the rest
        logger.warn(expected) { "Bulk mapping save failed, falling back to per-mapping save" }
        for (mapping in mappings) {
            try {
                importEngine.createAccountMapping(
                    valuePattern = mapping.valuePattern,
                    accountId = mapping.accountId,
                    strategyId = mapping.strategyId,
                )
            } catch (expectedMappingError: Exception) {
                // Don't log the pattern itself — it's derived from bank-file payee/account values (PII).
                logger.warn(expectedMappingError) { "Failed to save account mapping for account id ${mapping.accountId.id}" }
            }
        }
    }
}

/**
 * Creates the [accountsToCreate] (bulk, with per-account fallback) and returns the names actually
 * created. Failures are skipped — transfers referencing a missing account fail later.
 */
private suspend fun createNewAccounts(
    importEngine: ImportEngine,
    accountsToCreate: List<NewAccount>,
    csvImportId: CsvImportId,
    firstRowByAccountName: Map<String, Long>,
): Set<String> {
    if (accountsToCreate.isEmpty()) return emptySet()
    val createdAccountNames = mutableSetOf<String>()
    val newAccounts =
        accountsToCreate.map { newAccount ->
            Account(
                id = AccountId(0),
                name = newAccount.name,
                openingDate = Clock.System.now(),
                categoryId = newAccount.categoryId,
            )
        }
    // CSV provenance pointing each account at the first row that referenced it (its creation row);
    // row-less when unknown.
    val sourceFor: (Account) -> Source = { account ->
        Source.Csv(csvImportId, firstRowByAccountName[account.name])
    }
    try {
        // Bulk-create all accounts in a single engine batch
        importEngine.createAccounts(newAccounts, sourceFor)
        newAccounts.forEach { account -> createdAccountNames.add(account.name) }
        logger.info { "Created ${accountsToCreate.size} new accounts" }
    } catch (expected: Exception) {
        // Bulk create failed — fall back to per-account creation to skip only failing accounts
        logger.warn(expected) { "Bulk account creation failed, falling back to per-account creation" }
        for (account in newAccounts) {
            try {
                importEngine.createAccount(account, sourceFor(account))
                createdAccountNames.add(account.name)
                logger.info { "Created new account: ${account.name}" }
            } catch (expectedAccountError: Exception) {
                logger.warn(expectedAccountError) { "Skipping account '${account.name}': ${expectedAccountError.message}" }
            }
        }
    }
    return createdAccountNames
}

/**
 * Maps each new account's final name to the index of the earliest row/record that referenced it (its
 * creation row), so account provenance can point at the relevant row. Keyed by the final (possibly
 * user-renamed) account name to match the accounts actually created. Shared with the QIF importer,
 * which reuses this CSV engine (QIF record indexes ARE these row indexes).
 */
fun buildFirstRowByAccountName(
    preparation: ImportPreparation,
    newAccountNames: Map<String, String>,
): Map<String, Long> {
    val firstRow = mutableMapOf<String, Long>()
    preparation.validTransfers
        .sortedBy { it.rowIndex }
        .forEach { transfer ->
            transfer.discoveredMappings.forEach { mapping ->
                val finalName =
                    (newAccountNames[mapping.targetAccountName] ?: mapping.targetAccountName).trim()
                if (finalName.isNotBlank() && finalName !in firstRow) {
                    firstRow[finalName] = transfer.rowIndex
                }
            }
        }
    return firstRow
}

/**
 * Applies [strategy] to [rows] of a single [csvImport] and writes back per-row statuses. Creates new
 * accounts the user accepted, persists/auto-captures account mappings, runs the central import engine,
 * and records the strategy application. Shared by the single-file dialog and the bulk path; the bulk
 * path passes [refreshViews] = false and refreshes once at the end. Mirrors QifImportApplier.runImport.
 */
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
suspend fun runCsvImport(
    csvImport: CsvImport,
    rows: List<CsvRow>,
    columns: List<CsvColumn>,
    strategy: CsvImportStrategy,
    basePrep: ImportPreparation,
    selectedExistingAccounts: Map<String, AccountId>,
    selectedNewAccountNames: Map<String, String>,
    selectedSourceAccountId: AccountId?,
    currencies: List<Currency>,
    accountMappingRepository: AccountMappingReadRepository,
    accountRepository: AccountReadRepository,
    maintenance: Maintenance,
    importEngine: ImportEngine,
    refreshViews: Boolean = true,
    cryptoAssets: List<CryptoAsset> = emptyList(),
    passThroughAccounts: List<PassThroughAccount> = emptyList(),
    onProgress: (suspend (ImportProgress) -> Unit)? = null,
    engineBatchSize: Int = Int.MAX_VALUE,
): CsvImportResult {
    logger.info { "Starting CSV import with ${basePrep.validTransfers.size} valid transfers" }

    val accountsToCreate =
        buildAccountsToCreate(
            preparation = basePrep,
            existingAccountSelections = selectedExistingAccounts,
            newAccountNames = selectedNewAccountNames,
        )
    val selectedMappingsToPersist =
        buildPendingAccountMappings(
            preparation = basePrep,
            accountSelections = selectedExistingAccounts,
            accountsById = accountRepository.getAllAccounts().first().associateBy { it.id },
        )
    persistMappingsWithFallback(importEngine, selectedMappingsToPersist)

    // Create new accounts first (skip failures - transfers using them will fail later).
    // Record CSV provenance pointing each account at the first row that referenced it.
    val firstRowByAccountName = buildFirstRowByAccountName(basePrep, selectedNewAccountNames)
    createNewAccounts(
        importEngine = importEngine,
        accountsToCreate = accountsToCreate,
        csvImportId = csvImport.id,
        firstRowByAccountName = firstRowByAccountName,
    )

    // No auto-capture of template/regex/exact mappings: the strategy re-derives the target account
    // name on every import, and a later rename is resolved via audit history (getPreviousAccountNames),
    // so these would be redundant (and, stored globally, would leak across strategies). Only explicit
    // "map to existing account" selections are persisted (above), since those are not re-derivable.

    // Re-map with new account IDs
    logger.info { "Re-mapping transfers with updated account IDs" }
    val updatedAccounts = accountRepository.getAllAccounts().first()
    val accountsByName = updatedAccounts.associateBy { it.name }
    val currenciesById = currencies.associateBy { it.id }
    val currenciesByCode = currencies.associateBy { it.code.uppercase() }

    // Duplicate detection now happens inside the central import engine, which
    // loads existing transfers itself — the mapper only maps rows to transfers.
    val latestAccountMappings =
        accountMappingRepository.getAllMappings().first()
    val historicalAccountNames = accountRepository.getPreviousAccountNames()

    val mapper =
        CsvTransferMapper(
            strategy = strategy,
            columns = columns,
            existingAccounts = accountsByName,
            existingCurrencies = currenciesById,
            existingCurrenciesByCode = currenciesByCode,
            existingCryptoByCode = cryptoAssets.associateBy { it.code.uppercase() },
            accountMappings = latestAccountMappings,
            historicalAccountNames = historicalAccountNames,
            sourceAccountOverride = selectedSourceAccountId,
            passThroughDetector = passThroughAccounts.takeIf { it.isNotEmpty() }?.let { PassThroughDetector(it) },
        )

    // Handle case when all rows are already processed (rows is already filtered by the caller)
    if (rows.isEmpty()) {
        logger.info { "No rows to process - all rows already imported" }
        return CsvImportResult(successCount = 0, failedRows = emptyList())
    }

    val finalPrep = mapper.prepareImport(rows)
    val validCount = finalPrep.validTransfers.size
    val errorCount = finalPrep.errorRows.size
    logger.info { "Prepared $validCount valid transfers, $errorCount error rows" }

    // Mark mapping errors as ERROR status in database and save error messages
    importEngine.applyCsvImportMutations(
        finalPrep.errorRows.flatMap { errorRow ->
            listOf(
                CsvImportMutation.UpdateRowStatus(csvImport.id, errorRow.rowIndex, ImportStatus.ERROR.name),
                CsvImportMutation.SaveError(csvImport.id, errorRow.rowIndex, errorRow.errorMessage),
            )
        },
    )

    // Pre-resolve attribute types
    val allAttributeTypeNames =
        finalPrep.validTransfers
            .flatMap { it.attributes }
            .map { it.first }
            .toSet()
    val attributeTypeIdByName = importEngine.getOrCreateAttributeTypes(allAttributeTypeNames.toList())

    logger.info { "Starting to import $validCount transfers" }

    // Convert attributes from (typeName, value) to NewAttribute
    fun attributesFor(attributes: List<Pair<String, String>>): List<NewAttribute> =
        attributes.mapNotNull { (typeName, value) ->
            val typeId = attributeTypeIdByName[typeName]
            if (typeId != null) NewAttribute(typeId, value) else null
        }

    // Build the unified import batch. Accounts were already resolved/created
    // above, so transfers carry Existing refs and the central engine only
    // dedupes, writes transfers, applies updates and records sources.
    val uniqueIdTypeNames =
        strategy.attributeMappings
            .filter { it.isUniqueIdentifier }
            .map { it.attributeTypeName }
            .toSet()

    // Resolve a single consolidated "<strategy> Fees" account when any row carries a fee, so per-row
    // fees are modelled as their own movements linked to the main transfer.
    val feeAccountId: AccountId? =
        if (finalPrep.validTransfers.any { it.feeAmount != null }) {
            val feeAccountName = "${strategy.name} Fees"
            accountsByName[feeAccountName]?.id
                ?: importEngine.createAccount(
                    Account(
                        id = AccountId(0),
                        name = feeAccountName,
                        openingDate = Clock.System.now(),
                    ),
                    Source.Csv(csvImport.id),
                )
        } else {
            null
        }

    // Rows carrying a credited leg (Currency != To Currency) are cross-asset conversions → trades.
    val importTrades =
        finalPrep.validTransfers.mapNotNull { row ->
            val credit = row.tradeTo ?: return@mapNotNull null
            ImportTradeIntent(
                key = LocalTradeKey("csv-${csvImport.id.id}-${row.rowIndex}"),
                source = Source.Csv(csvImport.id),
                timestamp = row.transfer.timestamp,
                description = row.transfer.description,
                fromAccountId = row.transfer.sourceAccountId,
                fromAmount = row.transfer.amount,
                toAccountId = row.transfer.targetAccountId,
                toAmount = credit,
            )
        }

    // A trade carries no fee field, so a conversion row that also has a fee would otherwise drop it.
    // Emit each such fee as its own standalone movement (source account -> "<strategy> Fees") so the
    // money isn't lost. (Not produced by the current built-in strategies, but keeps the path honest.)
    val tradeFeeTransfers =
        finalPrep.validTransfers.mapNotNull { row ->
            if (row.tradeTo == null) return@mapNotNull null
            val fee = row.feeAmount ?: return@mapNotNull null
            val feeAcct = feeAccountId ?: return@mapNotNull null
            ImportTransfer(
                rowKey = ImportRowKey.CsvRow(row.rowIndex),
                fromAccount = AccountRef.Existing(row.transfer.sourceAccountId),
                toAccount = AccountRef.Existing(feeAcct),
                source = Source.Csv(csvImport.id),
                timestamp = row.transfer.timestamp,
                description = "${row.transfer.description} (fee)",
                amount = fee,
            )
        }

    // Asset-conversion linking: pair each debit leg to a credit leg (same pairing key, nearest
    // timestamp within the configured window) so the engine links them with the conversion
    // relationship. Handles both 1:1 swaps and N-debits -> 1-credit dust events (many debits share the
    // one credit). Debits with no in-window credit are left unlinked (still valid, balance-correct).
    val conversionConfig = strategy.conversionConfig
    val conversionLinkByRow: Map<Long, BatchRelationship> =
        if (conversionConfig == null) {
            emptyMap()
        } else {
            val window = conversionConfig.pairingWindowSeconds.seconds
            val credits = finalPrep.validTransfers.filter { it.conversionLeg?.side == ConversionSide.CREDIT }
            finalPrep.validTransfers
                .filter { it.conversionLeg?.side == ConversionSide.DEBIT }
                .mapNotNull { debit ->
                    val key = debit.conversionLeg!!.pairingKey
                    val match =
                        credits
                            .filter { it.conversionLeg!!.pairingKey == key }
                            .minByOrNull { (it.transfer.timestamp - debit.transfer.timestamp).absoluteValue }
                            ?.takeIf { (it.transfer.timestamp - debit.transfer.timestamp).absoluteValue <= window }
                            ?: return@mapNotNull null
                    debit.rowIndex to
                        BatchRelationship(
                            relatedRowKey = ImportRowKey.CsvRow(match.rowIndex),
                            typeName = conversionConfig.relationshipTypeName,
                        )
                }.toMap()
        }

    val importTransfers =
        finalPrep.validTransfers.filter { it.tradeTo == null }.map { row ->
            val uniqueKey =
                if (uniqueIdTypeNames.isEmpty()) {
                    null
                } else {
                    row.attributes
                        .filter { (name, _) -> name in uniqueIdTypeNames }
                        .associate { (name, value) -> name to value }
                }
            val fee =
                row.feeAmount?.let { feeMoney ->
                    ImportFee(
                        source = AccountRef.Existing(row.transfer.sourceAccountId),
                        target = AccountRef.Existing(feeAccountId!!),
                        amount = feeMoney,
                        description = "Fee",
                        relationshipTypeId = RelationshipTypeId(WellKnownIds.FEE_RELATIONSHIP_TYPE_ID),
                    )
                }
            // Pass-through (conduit) row: the mapper already routed the transfer's conduit side — the
            // target for an outgoing charge, the source for an incoming refund/cancellation; that side
            // is the chain's first conduit. Resolve the remaining chain conduits + the merchant account
            // the mapper created and let the engine add the spend legs (C1 -> C2, …, Cn -> merchant, or
            // reversed when incoming). accountsByName carries the created conduit + merchant ids.
            val passThrough =
                row.passThrough?.let { pt ->
                    val merchantId = pt.merchantAccountId ?: accountsByName[pt.merchantName]?.id
                    val firstConduitId = if (pt.incoming) row.transfer.sourceAccountId else row.transfer.targetAccountId
                    val innerConduitIds = pt.conduitNames.drop(1).map { accountsByName[it]?.id }
                    if (merchantId == null || innerConduitIds.any { it == null }) {
                        null
                    } else {
                        val nodes = listOf(firstConduitId) + innerConduitIds.filterNotNull() + merchantId
                        collapsePassThroughChain(nodes, pt.spendDescriptions)?.let { (keptNodes, keptDescriptions) ->
                            ImportPassThrough(
                                conduits = keptNodes.dropLast(1).map { AccountRef.Existing(it) },
                                merchantTarget = AccountRef.Existing(keptNodes.last()),
                                amount = row.transfer.amount,
                                spendDescriptions = keptDescriptions,
                                relationshipTypeId = RelationshipTypeId(pt.relationshipTypeId),
                                incoming = pt.incoming,
                            )
                        }
                    }
                }
            ImportTransfer(
                rowKey = ImportRowKey.CsvRow(row.rowIndex),
                fromAccount = AccountRef.Existing(row.transfer.sourceAccountId),
                toAccount = AccountRef.Existing(row.transfer.targetAccountId),
                source = Source.Csv(csvImport.id),
                timestamp = row.transfer.timestamp,
                description = row.transfer.description,
                amount = row.transfer.amount,
                attributes = attributesFor(row.attributes),
                uniqueKey = uniqueKey,
                fee = fee,
                passThrough = passThrough,
                batchRelationships = listOfNotNull(conversionLinkByRow[row.rowIndex]),
            )
        }

    val batch =
        ImportBatch(
            transfers = importTransfers + tradeFeeTransfers,
            trades = importTrades,
            dedupePolicy =
                if (uniqueIdTypeNames.isEmpty()) {
                    // Cross-source reconciliation is opt-in per strategy: rows recording a movement
                    // another export already imported (same accounts+amount within the window) are
                    // kept but excluded+linked instead of double-counting. Mirrors the API importer.
                    val reconcileWindow = strategy.crossSourceReconcileWindowSeconds?.seconds
                    DedupePolicy.FuzzyAllFields(
                        reconcileWindow = reconcileWindow,
                        reconciledExclusionAttributeTypeId =
                            reconcileWindow?.let { AttributeTypeId(WellKnownIds.EXCLUDED_ATTR_TYPE_ID) },
                        reconciledRelationshipTypeId =
                            reconcileWindow?.let { RelationshipTypeId(WellKnownIds.RECONCILED_RELATIONSHIP_TYPE_ID) },
                    )
                } else {
                    DedupePolicy.UniqueIdentifier
                },
            uniqueKeyExtractor =
                if (uniqueIdTypeNames.isEmpty()) {
                    null
                } else {
                    ExistingUniqueKeyExtractor { transfer ->
                        transfer.attributes
                            .filter { it.attributeType.name in uniqueIdTypeNames }
                            .associate { it.attributeType.name to it.value }
                    }
                },
        )

    val importResult = importEngine.import(batch, onProgress = onProgress, batchSize = engineBatchSize)

    // Write back per-row CSV statuses from the engine outcome.
    val importedStatuses = mutableMapOf<Long, TransferId?>()
    val duplicateStatuses = mutableMapOf<Long, TransferId?>()
    val updatedStatuses = mutableMapOf<Long, TransferId?>()
    for ((rowKey, outcome) in importResult.rowOutcomes) {
        val rowIndex = (rowKey as ImportRowKey.CsvRow).rowIndex
        when (outcome.status) {
            ImportStatus.IMPORTED -> importedStatuses[rowIndex] = outcome.transferId
            ImportStatus.DUPLICATE -> duplicateStatuses[rowIndex] = outcome.transferId
            ImportStatus.UPDATED -> updatedStatuses[rowIndex] = outcome.transferId
            ImportStatus.ERROR -> Unit
        }
    }
    // Cross-asset conversion rows are imported as trades, which the engine reports via createdTradeIds
    // (keyed by LocalTradeKey), NOT via rowOutcomes. Mark those rows IMPORTED — or DUPLICATE when the
    // intent matched an existing identical trade (createTrade is idempotent, e.g. a re-import or the
    // same event arriving from another export) — and clear their errors too, otherwise a converted row
    // keeps a stale ERROR status and gets reprocessed on the next import. The key is
    // "csv-<importId>-<rowIndex>".
    val tradeKeyPrefix = "csv-${csvImport.id.id}-"

    fun LocalTradeKey.rowIndexOrNull(): Long? =
        if (value.startsWith(tradeKeyPrefix)) value.removePrefix(tradeKeyPrefix).toLongOrNull() else null

    val tradeRowsByStatus =
        importResult.createdTradeIds.keys
            .mapNotNull { key -> key.rowIndexOrNull()?.let { key to it } }
            .groupBy(
                keySelector = { (key, _) -> if (key in importResult.dedupedTradeKeys) ImportStatus.DUPLICATE else ImportStatus.IMPORTED },
                valueTransform = { (_, rowIndex) -> rowIndex },
            )

    val statusMutations = mutableListOf<CsvImportMutation>()
    if (importedStatuses.isNotEmpty()) {
        statusMutations += CsvImportMutation.UpdateRowStatuses(csvImport.id, ImportStatus.IMPORTED.name, importedStatuses)
        statusMutations += CsvImportMutation.ClearErrors(csvImport.id, importedStatuses.keys.toList())
    }
    for ((status, rowIndices) in tradeRowsByStatus) {
        statusMutations +=
            CsvImportMutation.UpdateRowStatuses(
                csvImport.id,
                status.name,
                rowIndices.associateWith { null },
            )
        statusMutations += CsvImportMutation.ClearErrors(csvImport.id, rowIndices)
    }
    if (duplicateStatuses.isNotEmpty()) {
        statusMutations += CsvImportMutation.UpdateRowStatuses(csvImport.id, ImportStatus.DUPLICATE.name, duplicateStatuses)
        // A row retried from ERROR can resolve as DUPLICATE; clear its stale error like imported/updated do.
        statusMutations += CsvImportMutation.ClearErrors(csvImport.id, duplicateStatuses.keys.toList())
    }
    if (updatedStatuses.isNotEmpty()) {
        statusMutations += CsvImportMutation.UpdateRowStatuses(csvImport.id, ImportStatus.UPDATED.name, updatedStatuses)
        statusMutations += CsvImportMutation.ClearErrors(csvImport.id, updatedStatuses.keys.toList())
    }
    importEngine.applyCsvImportMutations(statusMutations)
    val newTradeCount = tradeRowsByStatus[ImportStatus.IMPORTED]?.size ?: 0
    val dedupedTradeCount = tradeRowsByStatus[ImportStatus.DUPLICATE]?.size ?: 0
    val successCount = importResult.transfersImported + importResult.updated + newTradeCount
    val duplicateCount = importResult.duplicates + dedupedTradeCount

    logger.info {
        "Transfer import complete: $successCount imported/updated, $duplicateCount duplicate(s)"
    }

    // Refresh materialized views so transfers are visible (skipped in bulk; refreshed once at the end)
    if (refreshViews) {
        logger.info { "Refreshing materialized views" }
        maintenance.refreshMaterializedViews()
    }

    if ((successCount + duplicateCount) > 0) {
        recordCsvApplication(importEngine, csvImport.id, strategy)
    }

    logger.info { "Import completed successfully" }

    // The engine import is atomic: any engine failure throws to the caller. The only per-row failures
    // are mapping errors detected before the import, so surface those to the dialog.
    return CsvImportResult(
        successCount = successCount,
        failedRows = finalPrep.errorRows.map { CsvImportResult.FailedRow(it.rowIndex, it.errorMessage) },
        duplicateCount = duplicateCount,
    )
}
