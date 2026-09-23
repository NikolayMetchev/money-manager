package com.moneymanager.ui.screens.csv

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.csvimporter.AttributeAccountMatcher
import com.moneymanager.csvimporter.CsvImportResult
import com.moneymanager.csvimporter.CsvTransferMapper
import com.moneymanager.csvimporter.ImportPreparation
import com.moneymanager.csvimporter.buildCreatedAccountNameOverrides
import com.moneymanager.csvimporter.buildPendingAccountMappings
import com.moneymanager.csvimporter.ensureCryptoAssets
import com.moneymanager.csvimporter.hasBlankNewAccountNames
import com.moneymanager.csvimporter.runCsvImport
import com.moneymanager.csvimporter.selectForCsv
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.model.accountmapping.AccountMapping
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.repository.AccountAttributeReadRepository
import com.moneymanager.domain.repository.AccountMappingReadRepository
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.CryptoReadRepository
import com.moneymanager.domain.repository.CsvImportStrategyReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.domain.repository.PassThroughAccountReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.PassThroughDetector
import com.moneymanager.ui.components.AccountPicker
import com.moneymanager.ui.components.LoadingTextButton
import com.moneymanager.ui.components.imports.ImportPreviewSection
import com.moneymanager.ui.components.imports.NewAccountResolutionSection
import com.moneymanager.ui.components.imports.StrategySelector
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.lighthousegames.logging.logging

private val logger = logging()

// QIF reuses the CSV import engine, so its apply dialog/applier mirror this one by design.
@Suppress("DuplicatedCode")
@Composable
fun ApplyStrategyDialog(
    csvImport: CsvImport,
    rows: List<CsvRow>,
    csvImportStrategyRepository: CsvImportStrategyReadRepository,
    accountMappingRepository: AccountMappingReadRepository,
    accountRepository: AccountReadRepository,
    accountAttributeRepository: AccountAttributeReadRepository,
    categoryRepository: CategoryReadRepository,
    currencyRepository: CurrencyReadRepository,
    personRepository: PersonReadRepository,
    passThroughAccountRepository: PassThroughAccountReadRepository,
    cryptoRepository: CryptoReadRepository,
    maintenance: Maintenance,
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
    val passThroughAccounts by passThroughAccountRepository
        .getAll()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())
    val passThroughDetector = passThroughAccounts.takeIf { it.isNotEmpty() }?.let { PassThroughDetector(it) }
    val accountAttributes by accountAttributeRepository
        .getAll()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())

    var selectedStrategy by remember { mutableStateOf<CsvImportStrategy?>(null) }
    var selectedSourceAccountId by remember { mutableStateOf<AccountId?>(null) }
    var baseImportPreparation by remember { mutableStateOf<ImportPreparation?>(null) }
    var importPreparation by remember { mutableStateOf<ImportPreparation?>(null) }
    var accountMappings by remember { mutableStateOf<List<AccountMapping>>(emptyList()) }
    var historicalAccountNames by remember { mutableStateOf<Map<String, AccountId>>(emptyMap()) }
    var selectedExistingAccounts by remember { mutableStateOf<Map<String, AccountId>>(emptyMap()) }
    var selectedNewAccountNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var isImporting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    // Already-existing crypto assets, so the preview resolves crypto tickers that were created by an
    // earlier import. Brand-new tickers are created at import time (below), not during the read-only
    // preview. Refreshed after a successful import.
    var cryptoAssets by remember { mutableStateOf<List<CryptoAsset>>(emptyList()) }

    // Strategies whose SOURCE_ACCOUNT mapping resolves per-row (e.g. by currency) need no
    // user-selected source account; the mapping decides the account for each row.
    val sourceAccountMapping = selectedStrategy?.config?.fieldMappings?.get(TransferField.SOURCE_ACCOUNT)
    val strategyHasPerRowSource = sourceAccountMapping != null && sourceAccountMapping !is HardCodedAccountMapping

    // Load account mappings when strategy is selected and pre-populate source account from strategy
    LaunchedEffect(selectedStrategy) {
        selectedStrategy?.let { strategy ->
            accountMappings = accountMappingRepository.getAllMappings().first()
            selectedExistingAccounts = emptyMap()
            selectedNewAccountNames = emptyMap()
            // Pre-populate source account from the strategy's SOURCE_ACCOUNT mapping if present.
            // This runs whenever the strategy changes, so switching strategies updates the
            // pre-selected source account to match the new strategy's default.
            when (val strategySourceMapping = strategy.config.fieldMappings[TransferField.SOURCE_ACCOUNT]) {
                is HardCodedAccountMapping -> selectedSourceAccountId = strategySourceMapping.accountId
                // Per-row mapping: clear any override left over from a previously selected strategy
                null -> Unit
                else -> selectedSourceAccountId = null
            }
        }
    }

    // Load former account names (audit history) once, so the preview resolves renamed accounts the
    // same way the actual import will.
    LaunchedEffect(Unit) {
        historicalAccountNames = accountRepository.getPreviousAccountNames()
        cryptoAssets = cryptoRepository.getAllCryptoAssets().first()
    }

    // Auto-select matching strategy when strategies load (filename/content-aware selection)
    LaunchedEffect(strategies, csvImport.columns, rows) {
        if (selectedStrategy == null && strategies.isNotEmpty()) {
            val matching = strategies.selectForCsv(csvImport.originalFileName, csvImport.columns, rows)
            selectedStrategy = matching ?: strategies.firstOrNull()
        }
    }

    // Filter to only show rows that will be processed: ERROR status or no status (never processed)
    val rowsToProcess = rows.filter { row -> row.importStatus == null || row.importStatus == ImportStatus.ERROR }

    // Prepare baseline import preview from persisted mappings only.
    LaunchedEffect(
        selectedStrategy,
        selectedSourceAccountId,
        rowsToProcess,
        accounts,
        currencies,
        accountMappings,
        historicalAccountNames,
        cryptoAssets,
    ) {
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
                            existingCryptoByCode = cryptoAssets.associateBy { it.code.uppercase() },
                            accountMappings = accountMappings,
                            historicalAccountNames = historicalAccountNames,
                            sourceAccountOverride = selectedSourceAccountId,
                            passThroughDetector = passThroughDetector,
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
        historicalAccountNames,
        cryptoAssets,
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
                            accountSelections = selectedExistingAccounts,
                            accountsById = accounts.associateBy { it.id },
                        )
                    val mapper =
                        CsvTransferMapper(
                            strategy = strategy,
                            columns = csvImport.columns,
                            existingAccounts = accountsByName,
                            existingCurrencies = currenciesById,
                            existingCurrenciesByCode = currenciesByCode,
                            existingCryptoByCode = cryptoAssets.associateBy { it.code.uppercase() },
                            accountMappings = accountMappings + previewMappings,
                            historicalAccountNames = historicalAccountNames,
                            sourceAccountOverride = selectedSourceAccountId,
                            passThroughDetector = passThroughDetector,
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

                    isImporting = true
                    errorMessage = null

                    scope.launch {
                        try {
                            // Create a crypto asset for every non-fiat ticker in the strategy's currency
                            // column before mapping, so crypto rows resolve instead of failing with
                            // "Currency not found" (upsert is idempotent). Same step the bulk/re-import
                            // paths run; done here at import time (not during the read-only preview).
                            val resolvedCrypto =
                                ensureCryptoAssets(
                                    strategy = strategy,
                                    columns = csvImport.columns,
                                    rows = rowsToProcess,
                                    currencies = currencies,
                                    importEngine = importEngine,
                                    cryptoRepository = cryptoRepository,
                                )
                            cryptoAssets = resolvedCrypto
                            val result =
                                runCsvImport(
                                    csvImport = csvImport,
                                    rows = rowsToProcess,
                                    columns = csvImport.columns,
                                    strategy = strategy,
                                    basePrep = basePrep,
                                    selectedExistingAccounts = selectedExistingAccounts,
                                    selectedNewAccountNames = selectedNewAccountNames,
                                    selectedSourceAccountId = selectedSourceAccountId,
                                    currencies = currencies,
                                    accountMappingRepository = accountMappingRepository,
                                    accountRepository = accountRepository,
                                    maintenance = maintenance,
                                    importEngine = importEngine,
                                    cryptoAssets = resolvedCrypto,
                                    passThroughAccounts = passThroughAccounts,
                                    attributeAccountMatchers = AttributeAccountMatcher.registry(accountAttributes),
                                )
                            onImportComplete(result)
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
