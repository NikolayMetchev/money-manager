@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.csvimporter

import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.accountmapping.AccountMapping
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csv.CsvImportId
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.passthrough.PassThroughAccount
import com.moneymanager.domain.repository.AccountMappingReadRepository
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.CsvImportMutation
import com.moneymanager.importengineapi.DedupePolicy
import com.moneymanager.importengineapi.ExistingUniqueKeyExtractor
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportFee
import com.moneymanager.importengineapi.ImportPassThrough
import com.moneymanager.importengineapi.ImportRowKey
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.PassThroughDetector
import com.moneymanager.importengineapi.applyCsvImportMutations
import com.moneymanager.importengineapi.createAccount
import com.moneymanager.importengineapi.createAccountMapping
import com.moneymanager.importengineapi.createAccountMappings
import com.moneymanager.importengineapi.createAccounts
import com.moneymanager.importengineapi.getOrCreateAttributeTypes
import kotlinx.coroutines.flow.first
import org.lighthousegames.logging.logging
import kotlin.time.Clock

private val logger = logging()

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
 * Refreshes materialized views once at the end. Reports progress via [onProgress].
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
    onProgress: (done: Int, total: Int) -> Unit,
    passThroughAccounts: List<PassThroughAccount> = emptyList(),
): CsvBulkResult {
    var filesImported = 0
    var transfers = 0
    var duplicates = 0
    var skippedNoStrategy = 0
    var failed = 0

    imports.forEachIndexed { index, listedImport ->
        onProgress(index, imports.size)
        // getAllImports() doesn't populate columns, so re-fetch the full import (which loads them)
        // for the column-based strategy match below.
        val csvImport = csvImportRepository.getImport(listedImport.id).first() ?: listedImport
        val columnNames = csvImport.columns.map { it.originalName }
        val matched = StrategyMatcher.findMatchingStrategy(columnNames, strategies)
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
                ) ?: return@forEachIndexed
            filesImported++
            transfers += result.successCount
            duplicates += result.duplicateCount
        } catch (expected: Exception) {
            logger.error(expected) { "Bulk CSV import failed for ${csvImport.originalFileName}: ${expected.message}" }
            failed++
        }
    }

    onProgress(imports.size, imports.size)
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
 * not auto-matched — used by the import-directory scanner, which pins one strategy per directory.
 * [refreshViews] = false lets a caller batch many files and refresh once at the end.
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
): CsvImportResult? {
    val allRows = csvImportRepository.getImportRows(csvImport.id, limit = csvImport.rowCount.coerceAtLeast(1), offset = 0)
    val rows = allRows.filter { it.importStatus == null || it.importStatus == ImportStatus.ERROR }
    if (rows.isEmpty()) return null

    // The shared override only applies to strategies that need a user-chosen source. A hard-coded
    // mapping resolves its own account; a per-row mapping decides per row.
    val effectiveSource = effectiveSourceFor(strategy, sourceAccountOverride)

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
        ).prepareImport(rows)

    return runCsvImport(
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
    )
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
): CsvTransferMapper =
    CsvTransferMapper(
        strategy = strategy,
        columns = columns,
        existingAccounts = accounts.associateBy { it.name },
        existingCurrencies = currencies.associateBy { it.id },
        existingCurrenciesByCode = currencies.associateBy { it.code.uppercase() },
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
    passThroughAccounts: List<PassThroughAccount> = emptyList(),
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

    val importTransfers =
        finalPrep.validTransfers.map { row ->
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
            // Pass-through (conduit) row: the mapper already routed the transfer's target to the conduit
            // (funding leg). Resolve the merchant account the mapper created and let the engine add the
            // spend leg conduit -> merchant. accountsByName carries the created conduit + merchant ids.
            val passThrough =
                row.passThrough?.let { pt ->
                    val merchantId = accountsByName[pt.merchantName]?.id
                    if (merchantId == null) {
                        null
                    } else {
                        ImportPassThrough(
                            conduit = AccountRef.Existing(row.transfer.targetAccountId),
                            merchantTarget = AccountRef.Existing(merchantId),
                            amount = row.transfer.amount,
                            spendDescription = pt.merchantName,
                            relationshipTypeId = RelationshipTypeId(pt.relationshipTypeId),
                        )
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
            )
        }

    val batch =
        ImportBatch(
            transfers = importTransfers,
            dedupePolicy =
                if (uniqueIdTypeNames.isEmpty()) {
                    DedupePolicy.FuzzyAllFields()
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

    val importResult = importEngine.import(batch)

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
    val statusMutations = mutableListOf<CsvImportMutation>()
    if (importedStatuses.isNotEmpty()) {
        statusMutations += CsvImportMutation.UpdateRowStatuses(csvImport.id, ImportStatus.IMPORTED.name, importedStatuses)
        statusMutations += CsvImportMutation.ClearErrors(csvImport.id, importedStatuses.keys.toList())
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
    val successCount = importResult.transfersImported + importResult.updated
    val duplicateCount = importResult.duplicates

    logger.info {
        "Transfer import complete: $successCount imported/updated, ${importResult.duplicates} duplicate(s)"
    }

    // Refresh materialized views so transfers are visible (skipped in bulk; refreshed once at the end)
    if (refreshViews) {
        logger.info { "Refreshing materialized views" }
        maintenance.refreshMaterializedViews()
    }

    if ((successCount + duplicateCount) > 0) {
        runCatching {
            importEngine.applyCsvImportMutations(
                listOf(
                    CsvImportMutation.RecordApplication(
                        id = csvImport.id,
                        strategyId = strategy.id,
                        strategyName = strategy.name,
                        appliedAt = Clock.System.now(),
                    ),
                ),
            )
        }.onFailure { error ->
            logger.warn {
                "Import application history could not be recorded for import ${csvImport.id}: ${error.message}"
            }
        }
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
