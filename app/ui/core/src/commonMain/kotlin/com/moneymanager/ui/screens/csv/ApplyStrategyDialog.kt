@file:OptIn(
    kotlin.time.ExperimentalTime::class,
    kotlin.uuid.ExperimentalUuidApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.moneymanager.ui.screens.csv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.moneymanager.database.csv.CsvImportProvenance
import com.moneymanager.database.csv.CsvTransferMapper
import com.moneymanager.database.csv.DiscoveredAccountMapping
import com.moneymanager.database.csv.ImportPreparation
import com.moneymanager.database.csv.NewAccount
import com.moneymanager.database.csv.StrategyMatcher
import com.moneymanager.domain.EntitySource
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.csvstrategy.CsvAccountMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.AttributeTypeRepository
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.domain.repository.CsvAccountMappingRepository
import com.moneymanager.domain.repository.CsvImportRepository
import com.moneymanager.domain.repository.CsvImportStrategyRepository
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.domain.repository.PersonAccountOwnershipRepository
import com.moneymanager.domain.repository.PersonRepository
import com.moneymanager.importer.ImportEngine
import com.moneymanager.importmodel.AccountRef
import com.moneymanager.importmodel.DedupePolicy
import com.moneymanager.importmodel.ExistingUniqueKeyExtractor
import com.moneymanager.importmodel.ImportBatch
import com.moneymanager.importmodel.ImportRowKey
import com.moneymanager.importmodel.ImportTransfer
import com.moneymanager.ui.components.AccountPicker
import com.moneymanager.ui.components.LoadingTextButton
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.lighthousegames.logging.logging
import kotlin.time.Clock
import kotlin.time.Instant

private val logger = logging()

/**
 * Result of a CSV import operation.
 */
data class CsvImportResult(
    val successCount: Int,
    val failedRows: List<FailedRow>,
) {
    data class FailedRow(
        val rowIndex: Long,
        val errorMessage: String,
    )
}

// QIF reuses the CSV import engine, so its apply dialog/applier mirror this one by design.
@Suppress("DuplicatedCode")
@Composable
fun ApplyStrategyDialog(
    csvImport: CsvImport,
    rows: List<CsvRow>,
    csvImportStrategyRepository: CsvImportStrategyRepository,
    csvAccountMappingRepository: CsvAccountMappingRepository,
    accountRepository: AccountRepository,
    categoryRepository: CategoryRepository,
    currencyRepository: CurrencyRepository,
    personRepository: PersonRepository,
    personAccountOwnershipRepository: PersonAccountOwnershipRepository,
    csvImportRepository: CsvImportRepository,
    attributeTypeRepository: AttributeTypeRepository,
    maintenance: Maintenance,
    entitySource: EntitySource,
    importEngine: ImportEngine,
    onDismiss: () -> Unit,
    onImportComplete: (CsvImportResult) -> Unit,
) {
    val scope = rememberSchemaAwareCoroutineScope()
    val strategies by csvImportStrategyRepository
        .getAllStrategies()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())
    val accounts by accountRepository
        .getAllAccounts()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())
    val currencies by currencyRepository
        .getAllCurrencies()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())

    var selectedStrategy by remember { mutableStateOf<CsvImportStrategy?>(null) }
    var selectedSourceAccountId by remember { mutableStateOf<AccountId?>(null) }
    var baseImportPreparation by remember { mutableStateOf<ImportPreparation?>(null) }
    var importPreparation by remember { mutableStateOf<ImportPreparation?>(null) }
    var accountMappings by remember { mutableStateOf<List<CsvAccountMapping>>(emptyList()) }
    var selectedExistingAccounts by remember { mutableStateOf<Map<String, AccountId>>(emptyMap()) }
    var selectedNewAccountNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var isImporting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Strategies whose SOURCE_ACCOUNT mapping resolves per-row (e.g. by currency) need no
    // user-selected source account; the mapping decides the account for each row.
    val sourceAccountMapping = selectedStrategy?.fieldMappings?.get(TransferField.SOURCE_ACCOUNT)
    val strategyHasPerRowSource = sourceAccountMapping != null && sourceAccountMapping !is HardCodedAccountMapping

    // Load account mappings when strategy is selected and pre-populate source account from strategy
    LaunchedEffect(selectedStrategy) {
        selectedStrategy?.let { strategy ->
            accountMappings = csvAccountMappingRepository.getMappingsForStrategy(strategy.id).first()
            selectedExistingAccounts = emptyMap()
            selectedNewAccountNames = emptyMap()
            // Pre-populate source account from the strategy's SOURCE_ACCOUNT mapping if present.
            // This runs whenever the strategy changes, so switching strategies updates the
            // pre-selected source account to match the new strategy's default.
            when (val strategySourceMapping = strategy.fieldMappings[TransferField.SOURCE_ACCOUNT]) {
                is HardCodedAccountMapping -> selectedSourceAccountId = strategySourceMapping.accountId
                // Per-row mapping: clear any override left over from a previously selected strategy
                null -> Unit
                else -> selectedSourceAccountId = null
            }
        }
    }

    // Auto-select matching strategy when strategies load
    LaunchedEffect(strategies, csvImport.columns) {
        if (selectedStrategy == null && strategies.isNotEmpty()) {
            val columnNames = csvImport.columns.map { it.originalName }
            val matching = StrategyMatcher.findMatchingStrategy(columnNames, strategies)
            selectedStrategy = matching ?: strategies.firstOrNull()
        }
    }

    // Filter to only show rows that will be processed: ERROR status or no status (never processed)
    val rowsToProcess = rows.filter { row -> row.importStatus == null || row.importStatus == ImportStatus.ERROR }

    // Prepare baseline import preview from persisted mappings only.
    LaunchedEffect(selectedStrategy, selectedSourceAccountId, rowsToProcess, accounts, currencies, accountMappings) {
        selectedStrategy?.let { strategy ->
            // An empty accounts list is fine: the mapper resolves unknown accounts to
            // placeholders and reports them as new accounts to create during import.
            if (currencies.isNotEmpty() && rowsToProcess.isNotEmpty()) {
                try {
                    val accountsByName = accounts.associateBy { it.name }
                    val currenciesById = currencies.associateBy { it.id }
                    val currenciesByCode = currencies.associateBy { it.code.uppercase() }
                    val mapper =
                        CsvTransferMapper(
                            strategy = strategy,
                            columns = csvImport.columns,
                            existingAccounts = accountsByName,
                            existingCurrencies = currenciesById,
                            existingCurrenciesByCode = currenciesByCode,
                            accountMappings = accountMappings,
                            sourceAccountOverride = selectedSourceAccountId,
                        )
                    val preparation = mapper.prepareImport(rowsToProcess)
                    baseImportPreparation = preparation
                    selectedExistingAccounts =
                        selectedExistingAccounts.filterKeys { selectedName ->
                            preparation.newAccounts.any { account -> account.name == selectedName }
                        }
                    selectedNewAccountNames =
                        preparation.newAccounts.associate { account ->
                            val existingName = selectedNewAccountNames[account.name]
                            account.name to (existingName ?: account.name)
                        }
                    errorMessage = null
                } catch (expected: Exception) {
                    errorMessage = "Failed to prepare import: ${expected.message}"
                    baseImportPreparation = null
                    importPreparation = null
                }
            } else if (rowsToProcess.isEmpty() && rows.isNotEmpty()) {
                // All rows already processed successfully
                errorMessage = "All rows have already been imported successfully."
                baseImportPreparation = null
                importPreparation = null
            }
        }
    }

    // Rebuild preview with any user-selected "map to existing account" overrides.
    LaunchedEffect(
        selectedStrategy,
        selectedSourceAccountId,
        rowsToProcess,
        accounts,
        currencies,
        accountMappings,
        baseImportPreparation,
        selectedExistingAccounts,
    ) {
        selectedStrategy?.let { strategy ->
            val basePreparation = baseImportPreparation
            if (currencies.isNotEmpty() && rowsToProcess.isNotEmpty() && basePreparation != null) {
                try {
                    val accountsByName = accounts.associateBy { it.name }
                    val currenciesById = currencies.associateBy { it.id }
                    val currenciesByCode = currencies.associateBy { it.code.uppercase() }
                    val previewMappings =
                        buildPendingAccountMappings(
                            preparation = basePreparation,
                            strategyId = strategy.id,
                            accountSelections = selectedExistingAccounts,
                        )
                    val mapper =
                        CsvTransferMapper(
                            strategy = strategy,
                            columns = csvImport.columns,
                            existingAccounts = accountsByName,
                            existingCurrencies = currenciesById,
                            existingCurrenciesByCode = currenciesByCode,
                            accountMappings = accountMappings + previewMappings,
                            sourceAccountOverride = selectedSourceAccountId,
                        )
                    importPreparation = mapper.prepareImport(rowsToProcess)
                    errorMessage = null
                } catch (expected: Exception) {
                    errorMessage = "Failed to prepare import: ${expected.message}"
                    importPreparation = null
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isImporting) onDismiss() },
        title = { Text("Apply Import Strategy") },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
            ) {
                // Strategy selector
                StrategySelector(
                    strategies = strategies,
                    selectedStrategy = selectedStrategy,
                    onStrategySelected = { selectedStrategy = it },
                    csvColumns = csvImport.columns,
                    enabled = !isImporting,
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Source account selector (hidden when the strategy resolves the source per-row)
                if (!strategyHasPerRowSource) {
                    AccountPicker(
                        selectedAccountId = selectedSourceAccountId,
                        onAccountSelected = { selectedSourceAccountId = it },
                        label = "Source Account",
                        accountRepository = accountRepository,
                        categoryRepository = categoryRepository,
                        personRepository = personRepository,
                        personAccountOwnershipRepository = personAccountOwnershipRepository,
                        entitySource = entitySource,
                        enabled = !isImporting,
                        isError = selectedSourceAccountId == null,
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                }

                baseImportPreparation
                    ?.takeIf { it.newAccounts.isNotEmpty() }
                    ?.let { basePrep ->
                        NewAccountResolutionSection(
                            newAccounts = basePrep.newAccounts.toList(),
                            discoveredMappings = basePrep.validTransfers.flatMap { it.discoveredMappings },
                            accounts = accounts,
                            selectedExistingAccounts = selectedExistingAccounts,
                            selectedNewAccountNames = selectedNewAccountNames,
                            onSelectionChanged = { accountName, selectedAccountId ->
                                selectedExistingAccounts =
                                    selectedExistingAccounts.toMutableMap().apply {
                                        if (selectedAccountId == null) {
                                            remove(accountName)
                                        } else {
                                            put(accountName, selectedAccountId)
                                        }
                                    }
                            },
                            onNewAccountNameChanged = { accountName, newName ->
                                selectedNewAccountNames =
                                    selectedNewAccountNames.toMutableMap().apply {
                                        put(accountName, newName)
                                    }
                            },
                            enabled = !isImporting,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                // Import preview
                importPreparation?.let { prep ->
                    ImportPreviewSection(
                        prep = prep,
                        renamedNewAccountNames =
                            buildCreatedAccountNameOverrides(
                                preparation = baseImportPreparation,
                                existingAccountSelections = selectedExistingAccounts,
                                newAccountNames = selectedNewAccountNames,
                            ),
                    )
                }

                errorMessage?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            LoadingTextButton(
                onClick = {
                    val strategy = selectedStrategy ?: return@LoadingTextButton
                    val basePrep = baseImportPreparation ?: return@LoadingTextButton
                    val prep = importPreparation ?: return@LoadingTextButton

                    isImporting = true
                    errorMessage = null

                    scope.launch {
                        try {
                            logger.info { "Starting CSV import with ${prep.validTransfers.size} valid transfers" }

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
                            if (selectedMappingsToPersist.isNotEmpty()) {
                                try {
                                    csvAccountMappingRepository.createMappings(selectedMappingsToPersist)
                                    logger.info { "Saved ${selectedMappingsToPersist.size} selected account mappings" }
                                } catch (expected: Exception) {
                                    // Batch is atomic, nothing was committed — retry per mapping
                                    // so one bad mapping doesn't block the rest
                                    logger.warn(expected) {
                                        "Bulk mapping save failed, falling back to per-mapping save"
                                    }
                                    for (mapping in selectedMappingsToPersist) {
                                        try {
                                            csvAccountMappingRepository.createMapping(
                                                strategyId = mapping.strategyId,
                                                columnName = mapping.columnName,
                                                valuePattern = mapping.valuePattern,
                                                accountId = mapping.accountId,
                                            )
                                        } catch (expectedMappingError: Exception) {
                                            logger.warn(expectedMappingError) {
                                                "Failed to save selected mapping '${mapping.valuePattern.pattern}'"
                                            }
                                        }
                                    }
                                }
                            }

                            // Create new accounts first (skip failures - transfers using them will fail later)
                            val createdAccountNames = mutableSetOf<String>()
                            if (accountsToCreate.isNotEmpty()) {
                                val newAccounts =
                                    accountsToCreate.map { newAccount ->
                                        Account(
                                            id = AccountId(0),
                                            name = newAccount.name,
                                            openingDate = Clock.System.now(),
                                            categoryId = newAccount.categoryId,
                                        )
                                    }
                                try {
                                    // Bulk-create all accounts in a single database transaction
                                    accountRepository.createAccountsBatch(newAccounts)
                                    createdAccountNames.addAll(accountsToCreate.map { it.name })
                                    logger.info { "Created ${accountsToCreate.size} new accounts" }
                                } catch (expected: Exception) {
                                    // Batch is atomic, nothing was committed — fall back to
                                    // per-account creation to skip only the failing accounts
                                    logger.warn(expected) {
                                        "Bulk account creation failed, falling back to per-account creation"
                                    }
                                    for (account in newAccounts) {
                                        try {
                                            accountRepository.createAccount(account)
                                            createdAccountNames.add(account.name)
                                            logger.info { "Created new account: ${account.name}" }
                                        } catch (expectedAccountError: Exception) {
                                            logger.warn(expectedAccountError) {
                                                "Skipping account '${account.name}': ${expectedAccountError.message}"
                                            }
                                        }
                                    }
                                }
                            }

                            // Auto-capture mappings for newly created accounts
                            if (createdAccountNames.isNotEmpty()) {
                                val updatedAccountsForMapping = accountRepository.getAllAccounts().first()
                                val accountsByNameForMapping = updatedAccountsForMapping.associateBy { it.name }

                                // Collect discovered mappings, separating regex matches from exact matches
                                val allDiscoveredMappings =
                                    prep.validTransfers.flatMap { it.discoveredMappings }

                                // For regex matches (matchedPattern != null), deduplicate by pattern
                                // This ensures only ONE mapping is created per regex rule
                                val regexMappings =
                                    allDiscoveredMappings
                                        .filter { it.matchedPattern != null }
                                        .distinctBy { it.matchedPattern }

                                // For exact matches (matchedPattern == null), deduplicate by csvValue
                                val exactMappings =
                                    allDiscoveredMappings
                                        .filter { it.matchedPattern == null }
                                        .distinctBy { it.csvValue }

                                val mappingCreatedAt = Clock.System.now()
                                val mappingsToCapture =
                                    (regexMappings + exactMappings).mapIndexedNotNull { index, discoveredMapping ->
                                        val createdAccountName =
                                            selectedNewAccountNames[discoveredMapping.targetAccountName]
                                                ?.trim()
                                                ?.takeIf { it.isNotBlank() }
                                                ?: discoveredMapping.targetAccountName

                                        // Look up the account by the final name created for this detected value
                                        val createdAccount =
                                            accountsByNameForMapping[createdAccountName]
                                                ?.takeIf { it.name in createdAccountNames }
                                                ?: return@mapIndexedNotNull null

                                        // Use the matched regex pattern if available,
                                        // otherwise create an exact-match pattern for the CSV value
                                        val matchedPattern = discoveredMapping.matchedPattern
                                        val pattern =
                                            if (matchedPattern != null) {
                                                Regex(matchedPattern, RegexOption.IGNORE_CASE)
                                            } else {
                                                Regex(
                                                    "^${Regex.escape(discoveredMapping.csvValue)}$",
                                                    RegexOption.IGNORE_CASE,
                                                )
                                            }
                                        CsvAccountMapping(
                                            id = -(index + 1).toLong(),
                                            strategyId = strategy.id,
                                            columnName = discoveredMapping.columnName,
                                            valuePattern = pattern,
                                            accountId = createdAccount.id,
                                            createdAt = mappingCreatedAt,
                                            updatedAt = mappingCreatedAt,
                                        )
                                    }

                                if (mappingsToCapture.isNotEmpty()) {
                                    try {
                                        // Bulk-save all auto-captured mappings in a single database transaction
                                        csvAccountMappingRepository.createMappings(mappingsToCapture)
                                        logger.info { "Auto-captured ${mappingsToCapture.size} account mappings" }
                                    } catch (expected: Exception) {
                                        // Batch is atomic, nothing was committed — retry per mapping
                                        // so one bad mapping doesn't block the rest
                                        logger.warn(expected) {
                                            "Bulk auto-capture failed, falling back to per-mapping save"
                                        }
                                        for (mapping in mappingsToCapture) {
                                            try {
                                                csvAccountMappingRepository.createMapping(
                                                    strategyId = mapping.strategyId,
                                                    columnName = mapping.columnName,
                                                    valuePattern = mapping.valuePattern,
                                                    accountId = mapping.accountId,
                                                )
                                            } catch (expectedMappingError: Exception) {
                                                logger.warn(expectedMappingError) {
                                                    "Failed to auto-capture mapping '${mapping.valuePattern.pattern}'"
                                                }
                                            }
                                        }
                                    }
                                }
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
                                    columns = csvImport.columns,
                                    existingAccounts = accountsByName,
                                    existingCurrencies = currenciesById,
                                    existingCurrenciesByCode = currenciesByCode,
                                    accountMappings = latestAccountMappings,
                                    sourceAccountOverride = selectedSourceAccountId,
                                )

                            // Handle case when all rows are already processed
                            // (rowsToProcess is already filtered at the top of the composable)
                            if (rowsToProcess.isEmpty()) {
                                logger.info { "No rows to process - all rows already imported" }
                                onImportComplete(
                                    CsvImportResult(
                                        successCount = 0,
                                        failedRows = emptyList(),
                                    ),
                                )
                                return@launch
                            }

                            val finalPrep = mapper.prepareImport(rowsToProcess)
                            val validCount = finalPrep.validTransfers.size
                            val errorCount = finalPrep.errorRows.size
                            logger.info { "Prepared $validCount valid transfers, $errorCount error rows" }

                            // Mark mapping errors as ERROR status in database and save error messages
                            for (errorRow in finalPrep.errorRows) {
                                csvImportRepository.updateRowStatus(
                                    csvImport.id,
                                    errorRow.rowIndex,
                                    ImportStatus.ERROR.name,
                                )
                                csvImportRepository.saveError(
                                    csvImport.id,
                                    errorRow.rowIndex,
                                    errorRow.errorMessage,
                                )
                            }

                            // Pre-resolve attribute types
                            val allAttributeTypeNames =
                                finalPrep.validTransfers
                                    .flatMap { it.attributes }
                                    .map { it.first }
                                    .toSet()
                            val attributeTypeIdByName =
                                allAttributeTypeNames.associateWith { name ->
                                    attributeTypeRepository.getOrCreate(name)
                                }

                            logger.info { "Starting to import $validCount transfers" }
                            // The engine import is atomic; any failure throws to the outer catch, so
                            // there is no partial per-row failure list to accumulate here.
                            val failedRows = emptyList<CsvImportResult.FailedRow>()
                            var successCount = 0
                            var duplicateCount = 0

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
                                    ImportTransfer(
                                        rowKey = ImportRowKey.CsvRow(row.rowIndex),
                                        source = AccountRef.Existing(row.transfer.sourceAccountId),
                                        target = AccountRef.Existing(row.transfer.targetAccountId),
                                        timestamp = row.transfer.timestamp,
                                        description = row.transfer.description,
                                        amount = row.transfer.amount,
                                        attributes = attributesFor(row.attributes),
                                        uniqueKey = uniqueKey,
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
                                    provenance = CsvImportProvenance(entitySource, csvImport.id),
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
                            if (importedStatuses.isNotEmpty()) {
                                csvImportRepository.updateRowStatusesBatch(
                                    csvImport.id,
                                    ImportStatus.IMPORTED.name,
                                    importedStatuses,
                                )
                                csvImportRepository.clearErrors(csvImport.id, importedStatuses.keys.toList())
                            }
                            if (duplicateStatuses.isNotEmpty()) {
                                csvImportRepository.updateRowStatusesBatch(
                                    csvImport.id,
                                    ImportStatus.DUPLICATE.name,
                                    duplicateStatuses,
                                )
                            }
                            if (updatedStatuses.isNotEmpty()) {
                                csvImportRepository.updateRowStatusesBatch(
                                    csvImport.id,
                                    ImportStatus.UPDATED.name,
                                    updatedStatuses,
                                )
                                csvImportRepository.clearErrors(csvImport.id, updatedStatuses.keys.toList())
                            }
                            successCount = importResult.transfersImported + importResult.updated
                            duplicateCount = importResult.duplicates

                            logger.info { "Transfer creation complete: $successCount successes, ${failedRows.size} failures" }

                            // Note: CSV rows are updated with status and transfer IDs during import loop above

                            // Refresh materialized views so transfers are visible
                            logger.info { "Refreshing materialized views" }
                            maintenance.refreshMaterializedViews()

                            if ((successCount + duplicateCount) > 0) {
                                runCatching {
                                    csvImportRepository.recordImportApplication(
                                        id = csvImport.id,
                                        strategyId = strategy.id,
                                        strategyName = strategy.name,
                                        appliedAt = Clock.System.now(),
                                    )
                                }.onFailure { error ->
                                    logger.warn {
                                        "Import application history could not be recorded for import ${csvImport.id}: ${error.message}"
                                    }
                                }
                            }

                            logger.info { "Import completed successfully" }

                            val result =
                                CsvImportResult(
                                    successCount = successCount,
                                    failedRows = failedRows,
                                )

                            if (successCount == 0 && failedRows.isNotEmpty()) {
                                // All rows failed - show error
                                errorMessage =
                                    "Import failed: all ${failedRows.size} rows failed due to database constraints"
                                isImporting = false
                            } else {
                                // At least some rows succeeded (or no rows at all)
                                if (failedRows.isNotEmpty()) {
                                    logger.info {
                                        "Import completed with $successCount successes and ${failedRows.size} failures"
                                    }
                                }
                                onImportComplete(result)
                            }
                        } catch (expected: Exception) {
                            logger.error(expected) { "Import failed: ${expected.message}" }
                            errorMessage = "Import failed: ${expected.message}"
                            isImporting = false
                        }
                    }
                },
                enabled =
                    !isImporting &&
                        selectedStrategy != null &&
                        (selectedSourceAccountId != null || strategyHasPerRowSource) &&
                        importPreparation != null &&
                        !hasBlankNewAccountNames(
                            preparation = baseImportPreparation,
                            existingAccountSelections = selectedExistingAccounts,
                            newAccountNames = selectedNewAccountNames,
                        ) &&
                        importPreparation?.validTransfers?.isNotEmpty() == true,
                loading = isImporting,
                label = "Import ${importPreparation?.validTransfers?.size ?: 0} Transfers",
                loadingIndicatorModifier = Modifier.padding(end = 8.dp),
                showLabelWhenLoading = true,
            )
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isImporting,
            ) {
                Text("Cancel")
            }
        },
    )
}

@Composable
internal fun StrategySelector(
    strategies: List<CsvImportStrategy>,
    selectedStrategy: CsvImportStrategy?,
    onStrategySelected: (CsvImportStrategy) -> Unit,
    csvColumns: List<CsvColumn>,
    enabled: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    val columnNames = csvColumns.map { it.originalName }

    Column {
        Text(
            text = "Select Strategy",
            style = MaterialTheme.typography.titleSmall,
        )

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { if (enabled) expanded = !expanded },
        ) {
            ReadonlyDropdownField(
                value = selectedStrategy?.name ?: "No strategy selected",
                expanded = expanded,
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                enabled = enabled,
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                strategies.forEach { strategy ->
                    val isMatch = strategy.matchesColumns(columnNames.toSet())
                    DropdownMenuItem(
                        text = {
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(strategy.name)
                                if (isMatch) {
                                    Text(
                                        text = "Match",
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        },
                        onClick = {
                            onStrategySelected(strategy)
                            expanded = false
                        },
                    )
                }
            }
        }

        if (strategies.isEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "No strategies available. Create a strategy first.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
internal fun NewAccountResolutionSection(
    newAccounts: List<NewAccount>,
    discoveredMappings: List<DiscoveredAccountMapping>,
    accounts: List<Account>,
    selectedExistingAccounts: Map<String, AccountId>,
    selectedNewAccountNames: Map<String, String>,
    onSelectionChanged: (String, AccountId?) -> Unit,
    onNewAccountNameChanged: (String, String) -> Unit,
    enabled: Boolean,
) {
    Column {
        Text(
            text = "New Account Handling",
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text =
                "Choose whether to create each detected account or map it to an existing one. " +
                    "Selected mappings will be saved to the strategy when you import.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))

        newAccounts
            .sortedBy { it.name.lowercase() }
            .forEach { newAccount ->
                val matchCount = discoveredMappings.count { it.targetAccountName == newAccount.name }
                NewAccountResolutionRow(
                    detectedAccountName = newAccount.name,
                    matchCount = matchCount,
                    accounts = accounts,
                    selectedAccountId = selectedExistingAccounts[newAccount.name],
                    newAccountName = selectedNewAccountNames[newAccount.name] ?: newAccount.name,
                    onSelectionChanged = { accountId ->
                        onSelectionChanged(newAccount.name, accountId)
                    },
                    onNewAccountNameChanged = { newName ->
                        onNewAccountNameChanged(newAccount.name, newName)
                    },
                    enabled = enabled,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
    }
}

@Composable
private fun NewAccountResolutionRow(
    detectedAccountName: String,
    matchCount: Int,
    accounts: List<Account>,
    selectedAccountId: AccountId?,
    newAccountName: String,
    onSelectionChanged: (AccountId?) -> Unit,
    onNewAccountNameChanged: (String) -> Unit,
    enabled: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedAccount = accounts.find { it.id == selectedAccountId }
    val isCreateNewSelection = selectedAccountId == null && newAccountName != detectedAccountName
    val dropdownLabel =
        when {
            selectedAccount != null -> selectedAccount.name
            isCreateNewSelection && newAccountName.isNotBlank() -> "Create New Account: $newAccountName"
            isCreateNewSelection -> "Create New Account"
            else -> "Exact match: $detectedAccountName"
        }

    Column {
        Text(
            text = detectedAccountName,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text =
                if (matchCount == 1) {
                    "1 matching CSV value in this import"
                } else {
                    "$matchCount matching CSV values in this import"
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { if (enabled) expanded = !expanded },
        ) {
            ReadonlyDropdownField(
                value = dropdownLabel,
                expanded = expanded,
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                enabled = enabled,
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Exact match: $detectedAccountName") },
                    onClick = {
                        onSelectionChanged(null)
                        onNewAccountNameChanged(detectedAccountName)
                        expanded = false
                    },
                )
                DropdownMenuItem(
                    text = { Text("Create New Account") },
                    onClick = {
                        onSelectionChanged(null)
                        onNewAccountNameChanged("")
                        expanded = false
                    },
                )
                accounts
                    .sortedBy { it.name.lowercase() }
                    .forEach { account ->
                        DropdownMenuItem(
                            text = { Text(account.name) },
                            onClick = {
                                onSelectionChanged(account.id)
                                expanded = false
                            },
                        )
                    }
            }
        }
        if (isCreateNewSelection) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = newAccountName,
                onValueChange = onNewAccountNameChanged,
                label = { Text("New account name") },
                placeholder = { Text("Detected: $detectedAccountName") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = enabled,
                isError = newAccountName.isBlank(),
                supportingText = {
                    Text(
                        text =
                            if (newAccountName.isBlank()) {
                                "Enter the name to create for this detected account"
                            } else {
                                "This name will be created and used for future mappings"
                            },
                    )
                },
            )
        }
    }
}

@Composable
internal fun ImportPreviewSection(
    prep: ImportPreparation,
    renamedNewAccountNames: Map<String, String> = emptyMap(),
) {
    Column {
        // Summary stats
        Text(
            text = "Import Preview",
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Status breakdown if available
        if (prep.statusCounts.isNotEmpty()) {
            Text(
                text = "Status Breakdown:",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(modifier = Modifier.height(8.dp))
            StatCardRow(
                stats =
                    listOfNotNull(
                        prep.statusCounts[ImportStatus.IMPORTED]?.let { StatCardData("New", it, MaterialTheme.colorScheme.primary) },
                        prep.statusCounts[ImportStatus.DUPLICATE]?.let {
                            StatCardData(
                                "Duplicate",
                                it,
                                MaterialTheme.colorScheme.secondary,
                            )
                        },
                        prep.statusCounts[ImportStatus.UPDATED]?.let { StatCardData("Updated", it, MaterialTheme.colorScheme.tertiary) },
                    ),
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        StatCardRow(
            stats =
                listOf(
                    StatCardData("Valid", prep.validTransfers.size, MaterialTheme.colorScheme.primary),
                    StatCardData("Errors", prep.errorRows.size, MaterialTheme.colorScheme.error),
                    StatCardData("New Accounts", prep.newAccounts.size, MaterialTheme.colorScheme.tertiary),
                ),
        )

        // New accounts to create
        if (prep.newAccounts.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "New Accounts to Create:",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.shapes.small,
                        ).padding(8.dp),
            ) {
                prep.newAccounts
                    .map { account ->
                        account.copy(
                            name =
                                renamedNewAccountNames[account.name]
                                    ?.takeIf { it.isNotBlank() }
                                    ?: account.name,
                        )
                    }.forEach { account ->
                        Text(
                            text = "• ${account.name}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
            }
        }

        // Error rows
        if (prep.errorRows.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Rows with Errors (will be skipped):",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.errorContainer,
                            MaterialTheme.shapes.small,
                        ).padding(8.dp),
            ) {
                prep.errorRows.take(5).forEach { error ->
                    Text(
                        text = "Row ${error.rowIndex}: ${error.errorMessage}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                if (prep.errorRows.size > 5) {
                    Text(
                        text = "... and ${prep.errorRows.size - 5} more errors",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        // Preview of valid transfers
        if (prep.validTransfers.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Transfer Preview (first 5):",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(modifier = Modifier.height(4.dp))
            TransferPreviewTable(prep.validTransfers.take(5).map { it.transfer })
        }
    }
}

internal fun buildPendingAccountMappings(
    preparation: ImportPreparation,
    strategyId: CsvImportStrategyId,
    accountSelections: Map<String, AccountId>,
    now: Instant = Clock.System.now(),
): List<CsvAccountMapping> {
    if (accountSelections.isEmpty()) {
        return emptyList()
    }

    return preparation.validTransfers
        .asSequence()
        .flatMap { it.discoveredMappings }
        .filter { discoveredMapping -> discoveredMapping.targetAccountName in accountSelections }
        .map { discoveredMapping ->
            val selectedAccountId = accountSelections.getValue(discoveredMapping.targetAccountName)
            PendingAccountMappingKey(
                columnName = discoveredMapping.columnName,
                pattern =
                    discoveredMapping.matchedPattern
                        ?: "^${Regex.escape(discoveredMapping.csvValue)}$",
                accountId = selectedAccountId,
            )
        }.distinct()
        .toList()
        .mapIndexed { index, mapping ->
            CsvAccountMapping(
                id = -(index + 1).toLong(),
                strategyId = strategyId,
                columnName = mapping.columnName,
                valuePattern = Regex(mapping.pattern, RegexOption.IGNORE_CASE),
                accountId = mapping.accountId,
                createdAt = now,
                updatedAt = now,
            )
        }
}

internal fun buildCreatedAccountNameOverrides(
    preparation: ImportPreparation?,
    existingAccountSelections: Map<String, AccountId>,
    newAccountNames: Map<String, String>,
): Map<String, String> {
    val safePreparation = preparation ?: return emptyMap()
    return safePreparation.newAccounts
        .filter { it.name !in existingAccountSelections }
        .mapNotNull { account ->
            val renamed = newAccountNames[account.name]?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            account.name to renamed
        }.toMap()
}

internal fun buildAccountsToCreate(
    preparation: ImportPreparation,
    existingAccountSelections: Map<String, AccountId>,
    newAccountNames: Map<String, String>,
): List<NewAccount> =
    preparation.newAccounts
        .asSequence()
        .filter { it.name !in existingAccountSelections }
        .mapNotNull { account ->
            // A missing entry means the user kept the detected name
            val finalName =
                (newAccountNames[account.name] ?: account.name)
                    .trim()
                    .takeIf { it.isNotBlank() } ?: return@mapNotNull null
            NewAccount(
                name = finalName,
                categoryId = account.categoryId,
            )
        }.distinctBy { it.name }
        .toList()

internal fun hasBlankNewAccountNames(
    preparation: ImportPreparation?,
    existingAccountSelections: Map<String, AccountId>,
    newAccountNames: Map<String, String>,
): Boolean {
    val safePreparation = preparation ?: return false
    return safePreparation.newAccounts.any { account ->
        account.name !in existingAccountSelections &&
            // A missing entry means the user kept the detected name; only an explicit blank blocks
            (newAccountNames[account.name] ?: account.name).isBlank()
    }
}

private data class PendingAccountMappingKey(
    val columnName: String,
    val pattern: String,
    val accountId: AccountId,
)

@Composable
private fun StatCard(
    label: String,
    count: Int,
    color: Color,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            Modifier
                .background(
                    color.copy(alpha = 0.1f),
                    MaterialTheme.shapes.small,
                ).padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.headlineMedium,
            color = color,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
    }
}

private data class StatCardData(
    val label: String,
    val count: Int,
    val color: Color,
)

@Composable
private fun StatCardRow(stats: List<StatCardData>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        stats.forEach { stat ->
            StatCard(
                label = stat.label,
                count = stat.count,
                color = stat.color,
            )
        }
    }
}

@Composable
private fun TransferPreviewTable(transfers: List<Transfer>) {
    val scrollState = rememberScrollState()

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState),
    ) {
        Column {
            // Header
            Row(
                modifier =
                    Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                TableCell("Date", isHeader = true)
                TableCell("Description", isHeader = true, width = 200.dp)
                TableCell("Amount", isHeader = true)
            }
            // Data rows
            transfers.forEach { transfer ->
                Row(
                    modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    TableCell(transfer.timestamp.toString().take(10))
                    TableCell(transfer.description, width = 200.dp)
                    TableCell(transfer.amount.toDisplayValue().toString())
                }
            }
        }
    }
}

@Composable
private fun TableCell(
    text: String,
    isHeader: Boolean = false,
    width: Dp = 100.dp,
) {
    Box(
        modifier =
            Modifier
                .width(width)
                .padding(8.dp),
    ) {
        Text(
            text = text,
            style =
                if (isHeader) {
                    MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
                } else {
                    MaterialTheme.typography.bodySmall
                },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ReadonlyDropdownField(
    value: String,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = {},
        readOnly = true,
        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        modifier = modifier,
        enabled = enabled,
    )
}
