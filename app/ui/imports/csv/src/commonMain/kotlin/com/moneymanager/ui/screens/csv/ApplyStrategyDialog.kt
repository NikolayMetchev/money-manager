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
import com.moneymanager.csvimporter.CsvImportResult
import com.moneymanager.csvimporter.CsvTransferMapper
import com.moneymanager.csvimporter.DiscoveredAccountMapping
import com.moneymanager.csvimporter.ImportPreparation
import com.moneymanager.csvimporter.NewAccount
import com.moneymanager.csvimporter.buildCreatedAccountNameOverrides
import com.moneymanager.csvimporter.buildPendingAccountMappings
import com.moneymanager.csvimporter.ensureCryptoAssets
import com.moneymanager.csvimporter.hasBlankNewAccountNames
import com.moneymanager.csvimporter.runCsvImport
import com.moneymanager.csvimporter.selectForCsv
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.accountmapping.AccountMapping
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
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
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.navigation.linuxHorizontalScrollWheel
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
    val sourceAccountMapping = selectedStrategy?.fieldMappings?.get(TransferField.SOURCE_ACCOUNT)
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
            when (val strategySourceMapping = strategy.fieldMappings[TransferField.SOURCE_ACCOUNT]) {
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

@Composable
fun StrategySelector(
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
fun NewAccountResolutionSection(
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
fun ImportPreviewSection(
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
                .linuxHorizontalScrollWheel(scrollState)
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
