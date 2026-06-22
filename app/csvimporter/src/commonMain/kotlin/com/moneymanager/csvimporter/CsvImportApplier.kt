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
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csv.CsvImportId
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.csvstrategy.CsvAccountMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CsvAccountMappingReadRepository
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.CsvImportMutation
import com.moneymanager.importengineapi.DedupePolicy
import com.moneymanager.importengineapi.ExistingUniqueKeyExtractor
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportFee
import com.moneymanager.importengineapi.ImportRowKey
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.applyCsvImportMutations
import com.moneymanager.importengineapi.createAccount
import com.moneymanager.importengineapi.createAccounts
import com.moneymanager.importengineapi.createCsvMapping
import com.moneymanager.importengineapi.createCsvMappings
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
    csvAccountMappingRepository: CsvAccountMappingReadRepository,
    accountRepository: AccountReadRepository,
    csvImportRepository: CsvImportReadRepository,
    maintenance: Maintenance,
    importEngine: ImportEngine,
    onProgress: (done: Int, total: Int) -> Unit,
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
            val allRows = csvImportRepository.getImportRows(csvImport.id, limit = csvImport.rowCount.coerceAtLeast(1), offset = 0)
            val rows = allRows.filter { it.importStatus == null || it.importStatus == ImportStatus.ERROR }
            if (rows.isEmpty()) return@forEachIndexed

            // The shared override only applies to strategies that need a user-chosen source. A
            // hard-coded mapping resolves its own account; a per-row mapping decides per row.
            val effectiveSource = effectiveSourceFor(matched, sourceAccountOverride)

            // Re-fetch accounts so payee accounts created by earlier files are seen.
            val accounts = accountRepository.getAllAccounts().first()
            val mappings = csvAccountMappingRepository.getMappingsForStrategy(matched.id).first()
            val basePrep =
                buildCsvMapper(matched, csvImport.columns, accounts, currencies, mappings, effectiveSource)
                    .prepareImport(rows)

            val result =
                runCsvImport(
                    csvImport = csvImport,
                    rows = rows,
                    columns = csvImport.columns,
                    strategy = matched,
                    basePrep = basePrep,
                    selectedExistingAccounts = emptyMap(),
                    selectedNewAccountNames = emptyMap(),
                    selectedSourceAccountId = effectiveSource,
                    currencies = currencies,
                    csvAccountMappingRepository = csvAccountMappingRepository,
                    accountRepository = accountRepository,
                    maintenance = maintenance,
                    importEngine = importEngine,
                    refreshViews = false,
                )
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
    accountMappings: List<CsvAccountMapping>,
    sourceAccountOverride: AccountId?,
): CsvTransferMapper =
    CsvTransferMapper(
        strategy = strategy,
        columns = columns,
        existingAccounts = accounts.associateBy { it.name },
        existingCurrencies = currencies.associateBy { it.id },
        existingCurrenciesByCode = currencies.associateBy { it.code.uppercase() },
        accountMappings = accountMappings,
        sourceAccountOverride = sourceAccountOverride,
    )

/**
 * Saves [mappings] in a single batch, falling back to per-mapping saves if the atomic batch fails so
 * one bad mapping doesn't block the rest. [kind] just labels log messages ("selected"/"auto-captured").
 */
private suspend fun persistMappingsWithFallback(
    importEngine: ImportEngine,
    mappings: List<CsvAccountMapping>,
    kind: String,
) {
    if (mappings.isEmpty()) return
    try {
        importEngine.createCsvMappings(mappings)
        logger.info { "Saved ${mappings.size} $kind account mappings" }
    } catch (expected: Exception) {
        // Batch is atomic, nothing was committed — retry per mapping so one bad mapping doesn't block the rest
        logger.warn(expected) { "Bulk $kind mapping save failed, falling back to per-mapping save" }
        for (mapping in mappings) {
            try {
                importEngine.createCsvMapping(
                    strategyId = mapping.strategyId,
                    columnName = mapping.columnName,
                    valuePattern = mapping.valuePattern,
                    accountId = mapping.accountId,
                )
            } catch (expectedMappingError: Exception) {
                logger.warn(expectedMappingError) { "Failed to save $kind mapping '${mapping.valuePattern.pattern}'" }
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
 * Builds account mappings for the just-created accounts from the rows' discovered mappings: regex
 * matches deduped by pattern, exact matches deduped by CSV value, so only one mapping is created per
 * rule. Only mappings whose target was actually created are kept.
 */
private fun buildAutoCapturedMappings(
    accountsByName: Map<String, Account>,
    discoveredMappings: List<DiscoveredAccountMapping>,
    createdAccountNames: Set<String>,
    selectedNewAccountNames: Map<String, String>,
    strategyId: CsvImportStrategyId,
): List<CsvAccountMapping> {
    // Regex matches (matchedPattern != null) deduped by pattern; exact matches deduped by csvValue
    val regexMappings = discoveredMappings.filter { it.matchedPattern != null }.distinctBy { it.matchedPattern }
    val exactMappings = discoveredMappings.filter { it.matchedPattern == null }.distinctBy { it.csvValue }
    val mappingCreatedAt = Clock.System.now()
    return (regexMappings + exactMappings).mapIndexedNotNull { index, discoveredMapping ->
        val createdAccountName =
            selectedNewAccountNames[discoveredMapping.targetAccountName]
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: discoveredMapping.targetAccountName

        // Look up the account by the final name created for this detected value
        val createdAccount =
            accountsByName[createdAccountName]
                ?.takeIf { it.name in createdAccountNames }
                ?: return@mapIndexedNotNull null

        // Use the matched regex pattern if available, otherwise an exact-match pattern for the CSV value
        val matchedPattern = discoveredMapping.matchedPattern
        val pattern =
            if (matchedPattern != null) {
                Regex(matchedPattern, RegexOption.IGNORE_CASE)
            } else {
                Regex("^${Regex.escape(discoveredMapping.csvValue)}$", RegexOption.IGNORE_CASE)
            }
        CsvAccountMapping(
            id = -(index + 1).toLong(),
            strategyId = strategyId,
            columnName = discoveredMapping.columnName,
            valuePattern = pattern,
            accountId = createdAccount.id,
            createdAt = mappingCreatedAt,
            updatedAt = mappingCreatedAt,
        )
    }
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
    csvAccountMappingRepository: CsvAccountMappingReadRepository,
    accountRepository: AccountReadRepository,
    maintenance: Maintenance,
    importEngine: ImportEngine,
    refreshViews: Boolean = true,
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
            strategyId = strategy.id,
            accountSelections = selectedExistingAccounts,
        )
    persistMappingsWithFallback(importEngine, selectedMappingsToPersist, "selected")

    // Create new accounts first (skip failures - transfers using them will fail later).
    // Record CSV provenance pointing each account at the first row that referenced it.
    val firstRowByAccountName = buildFirstRowByAccountName(basePrep, selectedNewAccountNames)
    val createdAccountNames =
        createNewAccounts(
            importEngine = importEngine,
            accountsToCreate = accountsToCreate,
            csvImportId = csvImport.id,
            firstRowByAccountName = firstRowByAccountName,
        )

    // Auto-capture mappings for newly created accounts
    if (createdAccountNames.isNotEmpty()) {
        val mappingsToCapture =
            buildAutoCapturedMappings(
                accountsByName = accountRepository.getAllAccounts().first().associateBy { it.name },
                discoveredMappings = basePrep.validTransfers.flatMap { it.discoveredMappings },
                createdAccountNames = createdAccountNames,
                selectedNewAccountNames = selectedNewAccountNames,
                strategyId = strategy.id,
            )
        persistMappingsWithFallback(importEngine, mappingsToCapture, "auto-captured")
    }

    // Re-map with new account IDs
    logger.info { "Re-mapping transfers with updated account IDs" }
    val updatedAccounts = accountRepository.getAllAccounts().first()
    val accountsByName = updatedAccounts.associateBy { it.name }
    val currenciesById = currencies.associateBy { it.id }
    val currenciesByCode = currencies.associateBy { it.code.uppercase() }

    // Duplicate detection now happens inside the central import engine, which
    // loads existing transfers itself — the mapper only maps rows to transfers.
    val latestAccountMappings =
        csvAccountMappingRepository.getMappingsForStrategy(strategy.id).first()

    val mapper =
        CsvTransferMapper(
            strategy = strategy,
            columns = columns,
            existingAccounts = accountsByName,
            existingCurrencies = currenciesById,
            existingCurrenciesByCode = currenciesByCode,
            accountMappings = latestAccountMappings,
            sourceAccountOverride = selectedSourceAccountId,
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
