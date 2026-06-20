@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.ui.screens.qif

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
import com.moneymanager.csvimporter.ImportPreparation
import com.moneymanager.csvimporter.buildCreatedAccountNameOverrides
import com.moneymanager.csvimporter.buildPendingAccountMappings
import com.moneymanager.csvimporter.hasBlankNewAccountNames
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.csvstrategy.CsvAccountMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.qif.QifImport
import com.moneymanager.domain.model.qif.QifImportRecord
import com.moneymanager.domain.repository.AccountWriteRepository
import com.moneymanager.domain.repository.AttributeTypeWriteRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.CsvAccountMappingWriteRepository
import com.moneymanager.domain.repository.CsvImportStrategyWriteRepository
import com.moneymanager.domain.repository.CurrencyWriteRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.domain.repository.QifImportWriteRepository
import com.moneymanager.domain.repository.SettingsWriteRepository
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.qifimporter.QifCsvAdapter
import com.moneymanager.qifimporter.QifImportResult
import com.moneymanager.qifimporter.buildMapper
import com.moneymanager.qifimporter.runImport
import com.moneymanager.qifimporter.selectForQifContent
import com.moneymanager.qifimporter.withQifCurrency
import com.moneymanager.ui.components.AccountPicker
import com.moneymanager.ui.components.CurrencyPicker
import com.moneymanager.ui.components.LoadingTextButton
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.screens.csv.ImportPreviewSection
import com.moneymanager.ui.screens.csv.NewAccountResolutionSection
import com.moneymanager.ui.screens.csv.StrategySelector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.lighthousegames.logging.logging

private val logger = logging()

/**
 * Applies a (shared) CSV import strategy to a QIF import. QIF records are presented to the CSV
 * strategy engine as rows over fixed columns via [QifCsvAdapter], so this reuses the same
 * strategy/account-mapping machinery and helper UI as CSV. v1 always creates new transfers
 * (duplicate detection between records is not performed; file-level re-import is blocked by checksum).
 *
 * Structurally mirrors the CSV ApplyStrategyDialog by design, hence the DuplicatedCode suppression.
 */
@Suppress("DuplicatedCode")
@Composable
fun QifApplyStrategyDialog(
    qifImport: QifImport,
    records: List<QifImportRecord>,
    csvImportStrategyRepository: CsvImportStrategyWriteRepository,
    csvAccountMappingRepository: CsvAccountMappingWriteRepository,
    accountRepository: AccountWriteRepository,
    categoryRepository: CategoryReadRepository,
    currencyRepository: CurrencyWriteRepository,
    personRepository: PersonReadRepository,
    qifImportRepository: QifImportWriteRepository,
    attributeTypeRepository: AttributeTypeWriteRepository,
    settingsRepository: SettingsWriteRepository,
    maintenance: Maintenance,
    importEngine: ImportEngine,
    onDismiss: () -> Unit,
    onImportComplete: (QifImportResult) -> Unit,
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
    // QIF carries no currency (it is implicit per account); the user chooses it at import time.
    var selectedCurrencyId by remember { mutableStateOf<CurrencyId?>(null) }
    var baseImportPreparation by remember { mutableStateOf<ImportPreparation?>(null) }
    var importPreparation by remember { mutableStateOf<ImportPreparation?>(null) }
    var accountMappings by remember { mutableStateOf<List<CsvAccountMapping>>(emptyList()) }
    var selectedExistingAccounts by remember { mutableStateOf<Map<String, AccountId>>(emptyMap()) }
    var selectedNewAccountNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var isImporting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val rows = remember(records) { QifCsvAdapter.toRows(records) }

    // A strategy is QIF-compatible when it only references QIF's fixed columns. Exact column-set
    // equality is too brittle (a user may identify on a subset), so use a subset check; this also
    // excludes CSV strategies (Wise/Monzo) whose columns aren't a subset of QIF's.
    val qifStrategies =
        remember(strategies) {
            val qifHeaders = QifCsvAdapter.headers.toSet()
            strategies.filter { it.identificationColumns.isNotEmpty() && it.identificationColumns.all { col -> col in qifHeaders } }
        }

    val sourceAccountMapping = selectedStrategy?.fieldMappings?.get(TransferField.SOURCE_ACCOUNT)
    val strategyHasPerRowSource = sourceAccountMapping != null && sourceAccountMapping !is HardCodedAccountMapping

    LaunchedEffect(selectedStrategy) {
        selectedStrategy?.let { strategy ->
            accountMappings = csvAccountMappingRepository.getMappingsForStrategy(strategy.id).first()
            selectedExistingAccounts = emptyMap()
            selectedNewAccountNames = emptyMap()
            when (val strategySourceMapping = strategy.fieldMappings[TransferField.SOURCE_ACCOUNT]) {
                is HardCodedAccountMapping -> selectedSourceAccountId = strategySourceMapping.accountId
                null -> Unit
                else -> selectedSourceAccountId = null
            }
        }
    }

    LaunchedEffect(qifStrategies, rows) {
        if (selectedStrategy == null) {
            // Auto-detect the bank strategy from the file's content (e.g. Santander), with the generic
            // QIF strategy as the fallback. The user can still override via the selector below.
            selectedStrategy = qifStrategies.selectForQifContent(rows, QifCsvAdapter.columns)
        }
    }

    // Pre-select the source account used by the most recent QIF import, unless the strategy resolves
    // the source itself. Only applies a remembered account that still exists.
    LaunchedEffect(accounts, selectedStrategy) {
        val strategy = selectedStrategy ?: return@LaunchedEffect
        val hasHardcodedSource = strategy.fieldMappings[TransferField.SOURCE_ACCOUNT] is HardCodedAccountMapping
        if (selectedSourceAccountId == null && !strategyHasPerRowSource && !hasHardcodedSource) {
            val last = settingsRepository.getLastQifAccountId().first()
            if (last != null && accounts.any { it.id == last }) {
                selectedSourceAccountId = last
            }
        }
    }

    LaunchedEffect(currencies) {
        if (selectedCurrencyId == null && currencies.isNotEmpty()) {
            val defaultId = settingsRepository.getDefaultCurrencyId().first()
            selectedCurrencyId = defaultId?.takeIf { id -> currencies.any { it.id == id } } ?: currencies.firstOrNull()?.id
        }
    }

    LaunchedEffect(
        selectedStrategy,
        selectedSourceAccountId,
        selectedCurrencyId,
        rows,
        accounts,
        currencies,
        accountMappings,
        selectedExistingAccounts,
    ) {
        val strategy = selectedStrategy?.withQifCurrency(selectedCurrencyId)
        if (strategy != null && currencies.isNotEmpty() && rows.isNotEmpty()) {
            try {
                val mapper = buildMapper(strategy, accounts, currencies, accountMappings, selectedSourceAccountId)
                val base = mapper.prepareImport(rows)
                baseImportPreparation = base
                selectedNewAccountNames =
                    base.newAccounts.associate { account ->
                        account.name to (selectedNewAccountNames[account.name] ?: account.name)
                    }
                // Duplicate detection runs at import time (it queries existing transfers, which is
                // too heavy for the reactive preview); the preview shows the mapped transfers.
                val previewMappings =
                    buildPendingAccountMappings(base, strategy.id, selectedExistingAccounts)
                importPreparation =
                    buildMapper(strategy, accounts, currencies, accountMappings + previewMappings, selectedSourceAccountId)
                        .prepareImport(rows)
                errorMessage = null
            } catch (expected: Exception) {
                errorMessage = "Failed to prepare import: ${expected.message}"
                baseImportPreparation = null
                importPreparation = null
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isImporting) onDismiss() },
        title = { Text("Apply Import Strategy") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                StrategySelector(
                    strategies = qifStrategies,
                    selectedStrategy = selectedStrategy,
                    onStrategySelected = { selectedStrategy = it },
                    csvColumns = QifCsvAdapter.columns,
                    enabled = !isImporting,
                )
                if (qifStrategies.isEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "No QIF strategy matches this file yet. Close and use \"Create Strategy\" to make one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))

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

                // QIF files carry no currency, so it is chosen here rather than read from the data.
                CurrencyPicker(
                    selectedCurrencyId = selectedCurrencyId,
                    onCurrencySelected = { selectedCurrencyId = it },
                    label = "Currency",
                    currencyRepository = currencyRepository,
                    enabled = !isImporting,
                    isError = selectedCurrencyId == null,
                )
                Spacer(modifier = Modifier.height(16.dp))

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
                                        if (selectedAccountId == null) remove(accountName) else put(accountName, selectedAccountId)
                                    }
                            },
                            onNewAccountNameChanged = { accountName, newName ->
                                selectedNewAccountNames =
                                    selectedNewAccountNames.toMutableMap().apply { put(accountName, newName) }
                            },
                            enabled = !isImporting,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                importPreparation?.let { prep ->
                    ImportPreviewSection(
                        prep = prep,
                        renamedNewAccountNames =
                            buildCreatedAccountNameOverrides(baseImportPreparation, selectedExistingAccounts, selectedNewAccountNames),
                    )
                }

                errorMessage?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            LoadingTextButton(
                onClick = {
                    val strategy = selectedStrategy?.withQifCurrency(selectedCurrencyId) ?: return@LoadingTextButton
                    val basePrep = baseImportPreparation ?: return@LoadingTextButton
                    isImporting = true
                    errorMessage = null
                    scope.launch {
                        try {
                            val result =
                                runImport(
                                    qifImport = qifImport,
                                    rows = rows,
                                    strategy = strategy,
                                    basePrep = basePrep,
                                    selectedExistingAccounts = selectedExistingAccounts,
                                    selectedNewAccountNames = selectedNewAccountNames,
                                    selectedSourceAccountId = selectedSourceAccountId,
                                    currencies = currencies,
                                    csvAccountMappingRepository = csvAccountMappingRepository,
                                    accountRepository = accountRepository,
                                    qifImportRepository = qifImportRepository,
                                    attributeTypeRepository = attributeTypeRepository,
                                    maintenance = maintenance,
                                    importEngine = importEngine,
                                )
                            // Remember the source account so the next QIF import pre-selects it.
                            selectedSourceAccountId?.let { settingsRepository.setLastQifAccountId(it) }
                            onImportComplete(result)
                        } catch (expected: Exception) {
                            logger.error(expected) { "QIF import failed: ${expected.message}" }
                            errorMessage = "Import failed: ${expected.message}"
                            isImporting = false
                        }
                    }
                },
                enabled =
                    !isImporting &&
                        selectedStrategy != null &&
                        selectedCurrencyId != null &&
                        (selectedSourceAccountId != null || strategyHasPerRowSource) &&
                        importPreparation?.validTransfers?.isNotEmpty() == true &&
                        !hasBlankNewAccountNames(baseImportPreparation, selectedExistingAccounts, selectedNewAccountNames),
                loading = isImporting,
                label = "Import ${importPreparation?.validTransfers?.size ?: 0} Transfers",
                loadingIndicatorModifier = Modifier.padding(end = 8.dp),
                showLabelWhenLoading = true,
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isImporting) {
                Text("Cancel")
            }
        },
    )
}
