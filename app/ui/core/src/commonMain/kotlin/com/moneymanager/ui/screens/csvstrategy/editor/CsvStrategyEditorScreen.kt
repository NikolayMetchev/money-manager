@file:OptIn(
    kotlin.time.ExperimentalTime::class,
    kotlin.uuid.ExperimentalUuidApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.moneymanager.ui.screens.csvstrategy.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
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
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.EntitySource
import com.moneymanager.domain.model.csv.CsvImportId
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csvstrategy.CsvAccountMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.AttributeTypeRepository
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.domain.repository.CsvAccountMappingRepository
import com.moneymanager.domain.repository.CsvImportRepository
import com.moneymanager.domain.repository.CsvImportStrategyRepository
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.domain.repository.PersonAccountOwnershipRepository
import com.moneymanager.domain.repository.PersonRepository
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.screens.csvstrategy.AccountMappingEditorDialog
import com.moneymanager.ui.screens.csvstrategy.ColumnDetector
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Full-screen editor for creating or editing a CSV import strategy.
 *
 * Loads the CSV [csvImportId]'s columns and sample rows (and, when [strategyId] is non-null, the
 * existing strategy) from the repositories, then presents a tabbed form. Saving builds the strategy
 * via [buildStrategyFromFormState] and navigates back.
 */
@Composable
fun CsvStrategyEditorScreen(
    csvImportId: CsvImportId,
    strategyId: CsvImportStrategyId?,
    csvImportRepository: CsvImportRepository,
    csvImportStrategyRepository: CsvImportStrategyRepository,
    csvAccountMappingRepository: CsvAccountMappingRepository,
    accountRepository: AccountRepository,
    categoryRepository: CategoryRepository,
    currencyRepository: CurrencyRepository,
    attributeTypeRepository: AttributeTypeRepository,
    personRepository: PersonRepository,
    personAccountOwnershipRepository: PersonAccountOwnershipRepository,
    entitySource: EntitySource,
    onBack: () -> Unit,
) {
    val isEditMode = strategyId != null
    val scope = rememberSchemaAwareCoroutineScope()

    // Repository flows are collected via the schema-aware helper so database schema errors are
    // reported globally instead of cancelling the effect.
    val csvImport by csvImportRepository.getImport(csvImportId).collectAsStateWithSchemaErrorHandling(null)
    val accounts by accountRepository.getAllAccounts().collectAsStateWithSchemaErrorHandling(emptyList())
    val existingAttributeTypes by attributeTypeRepository.getAll().collectAsStateWithSchemaErrorHandling(emptyList())

    // Wrap the strategy in a load state so "still loading" is distinct from "loaded but missing"
    // (a deleted/invalid id), which is rendered as a terminal error rather than an infinite spinner.
    val strategyFlow =
        remember(strategyId) {
            (strategyId?.let { csvImportStrategyRepository.getStrategyById(it) } ?: flowOf(null))
                .map<CsvImportStrategy?, StrategyLoad> { StrategyLoad.Loaded(it) }
        }
    val strategyLoad by strategyFlow.collectAsStateWithSchemaErrorHandling(
        if (isEditMode) StrategyLoad.Loading else StrategyLoad.Loaded(null),
    )
    val existingStrategy = (strategyLoad as? StrategyLoad.Loaded)?.strategy
    val strategyLoaded = strategyLoad is StrategyLoad.Loaded

    val accountMappingsFlow =
        remember(strategyId) {
            strategyId?.let { csvAccountMappingRepository.getMappingsForStrategy(it) } ?: flowOf(emptyList())
        }
    val accountMappings by accountMappingsFlow.collectAsStateWithSchemaErrorHandling(emptyList())

    var rows by remember { mutableStateOf<List<CsvRow>>(emptyList()) }
    var rowsLoaded by remember { mutableStateOf(false) }
    var editingAccountMapping by remember { mutableStateOf<CsvAccountMapping?>(null) }
    var showAddAccountMappingDialog by remember { mutableStateOf(false) }

    LaunchedEffect(csvImportId, csvImport?.rowCount) {
        val import = csvImport ?: return@LaunchedEffect
        rows = csvImportRepository.getImportRows(csvImportId, limit = import.rowCount, offset = 0)
        rowsLoaded = true
    }

    val currentImport = csvImport
    val ready = currentImport != null && rowsLoaded && strategyLoaded
    if (!ready) {
        EditorPlaceholder(isEditMode = isEditMode, onBack = onBack) {
            CircularProgressIndicator()
        }
        return
    }

    if (isEditMode && existingStrategy == null) {
        EditorPlaceholder(isEditMode = true, onBack = onBack) {
            Text("Strategy not found.", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    val csvColumns = currentImport.columns
    val availableColumnNames = remember(csvColumns) { csvColumns.map { it.originalName }.toSet() }
    val initial =
        remember(existingStrategy, availableColumnNames) {
            existingStrategy?.let { extractFormStateFromStrategy(it, availableColumnNames) }
        }
    val editKey = strategyId?.toString() ?: "create:$csvImportId"
    val state = rememberCsvStrategyEditorState(editKey, initial, availableColumnNames)

    val firstRow = rows.firstOrNull()

    // Auto-detect columns on first load (create mode only).
    LaunchedEffect(csvColumns, firstRow) {
        if (!isEditMode) {
            val sampleValues =
                firstRow?.let { row ->
                    csvColumns.associate { col -> col.columnIndex to row.values.getOrNull(col.columnIndex).orEmpty() }
                }
            if (state.dateColumnName == null) {
                state.dateColumnName = ColumnDetector.suggestDateColumn(csvColumns, sampleValues)
            }
            if (state.timeColumnName == null) {
                state.timeColumnName = ColumnDetector.suggestTimeColumn(csvColumns, sampleValues)
            }
            if (state.descriptionColumnName == null) {
                state.descriptionColumnName = ColumnDetector.suggestDescriptionColumn(csvColumns)
            }
            if (state.amountColumnName == null) {
                state.amountColumnName = ColumnDetector.suggestAmountColumn(csvColumns, sampleValues)
            }
            if (state.targetAccountColumnName == null) {
                state.targetAccountColumnName = ColumnDetector.suggestPayeeColumn(csvColumns)
            }
            if (state.currencyColumnName == null) {
                state.currencyColumnName = ColumnDetector.suggestCurrencyColumn(csvColumns, sampleValues)
                if (state.currencyColumnName != null) {
                    state.currencyMode = CurrencyMode.FROM_COLUMN
                }
            }
        }
    }

    // Auto-detect fallback columns when the target column changes (not on initial edit load).
    LaunchedEffect(state.targetAccountColumnName, rows) {
        val primaryColumn = state.targetAccountColumnName
        if (primaryColumn != null && primaryColumn != state.initialTargetAccountColumnName && rows.isNotEmpty()) {
            state.targetAccountFallbackColumns =
                ColumnDetector.suggestFallbackColumns(primaryColumn = primaryColumn, columns = csvColumns, rows = rows)
        }
    }

    // Auto-detect fallback columns when the description column changes.
    LaunchedEffect(state.descriptionColumnName, rows) {
        val primaryColumn = state.descriptionColumnName
        if (primaryColumn != null && primaryColumn != state.initialDescriptionColumnName && rows.isNotEmpty()) {
            state.descriptionFallbackColumns =
                ColumnDetector.suggestFallbackColumns(primaryColumn = primaryColumn, columns = csvColumns, rows = rows)
        }
    }

    fun save() {
        if (!state.isValid) return
        state.isSaving = true
        state.errorMessage = null
        scope.launch {
            try {
                val now = Clock.System.now()
                val strategy =
                    buildStrategyFromFormState(
                        state = state.toFormState(),
                        id = existingStrategy?.id ?: CsvImportStrategyId(Uuid.random()),
                        createdAt = existingStrategy?.createdAt ?: now,
                        updatedAt = now,
                    )
                if (isEditMode) {
                    csvImportStrategyRepository.updateStrategy(strategy)
                } else {
                    csvImportStrategyRepository.createStrategy(strategy)
                }
                onBack()
            } catch (expected: Exception) {
                val action = if (isEditMode) "save" else "create"
                state.errorMessage = "Failed to $action strategy: ${expected.message}"
                state.isSaving = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        EditorHeader(
            isEditMode = isEditMode,
            saveEnabled = state.isValid && !state.isSaving,
            isSaving = state.isSaving,
            onBack = { if (!state.isSaving) onBack() },
            onSave = { save() },
        )
        PrimaryTabRow(selectedTabIndex = state.selectedTab.ordinal) {
            EditorTab.entries.forEach { tab ->
                Tab(
                    selected = state.selectedTab == tab,
                    onClick = { state.selectedTab = tab },
                    text = { Text(if (state.tabHasError(tab)) "${tab.title} •" else tab.title) },
                )
            }
        }
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
        ) {
            when (state.selectedTab) {
                EditorTab.GENERAL ->
                    GeneralTab(
                        state = state,
                        csvColumns = csvColumns,
                        rows = rows,
                        firstRow = firstRow,
                        enabled = !state.isSaving,
                    )
                EditorTab.ACCOUNTS ->
                    AccountsTab(
                        state = state,
                        csvColumns = csvColumns,
                        rows = rows,
                        firstRow = firstRow,
                        enabled = !state.isSaving,
                        accountRepository = accountRepository,
                        categoryRepository = categoryRepository,
                        personRepository = personRepository,
                        personAccountOwnershipRepository = personAccountOwnershipRepository,
                        entitySource = entitySource,
                    )
                EditorTab.AMOUNT_DATE ->
                    AmountDateTab(
                        state = state,
                        csvColumns = csvColumns,
                        firstRow = firstRow,
                        enabled = !state.isSaving,
                        currencyRepository = currencyRepository,
                    )
                EditorTab.ADVANCED ->
                    AdvancedTab(
                        state = state,
                        csvColumns = csvColumns,
                        firstRow = firstRow,
                        enabled = !state.isSaving,
                        existingAttributeTypes = existingAttributeTypes,
                        isEditMode = isEditMode,
                        accountMappings = accountMappings,
                        accounts = accounts,
                        onEditAccountMapping = { editingAccountMapping = it },
                        onDeleteAccountMapping = { mapping ->
                            scope.launch { csvAccountMappingRepository.deleteMapping(mapping.id) }
                        },
                        onAddAccountMapping = { showAddAccountMappingDialog = true },
                    )
            }

            state.errorMessage?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    editingAccountMapping?.let { mapping ->
        // Account mappings only exist in edit mode, so strategyId is non-null here; guard rather
        // than force-unwrap so a parameter transition can never crash the dialog.
        val currentStrategyId = strategyId
        if (currentStrategyId == null) {
            editingAccountMapping = null
            return@let
        }
        AccountMappingEditorDialog(
            existingMapping = mapping,
            strategyId = currentStrategyId,
            accounts = accounts,
            csvAccountMappingRepository = csvAccountMappingRepository,
            onDismiss = { editingAccountMapping = null },
        )
    }

    if (showAddAccountMappingDialog && strategyId != null) {
        AccountMappingEditorDialog(
            existingMapping = null,
            strategyId = strategyId,
            accounts = accounts,
            csvAccountMappingRepository = csvAccountMappingRepository,
            onDismiss = { showAddAccountMappingDialog = false },
        )
    }
}

/**
 * Screen header: back, title, and the save action.
 */
@Composable
private fun EditorHeader(
    isEditMode: Boolean,
    saveEnabled: Boolean,
    isSaving: Boolean,
    onBack: () -> Unit,
    onSave: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack, enabled = !isSaving) { Text("← Back") }
        Text(
            if (isEditMode) "Edit Import Strategy" else "Create Import Strategy",
            style = MaterialTheme.typography.titleMedium,
        )
        TextButton(onClick = onSave, enabled = saveEnabled) {
            if (isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(end = 8.dp),
                    strokeWidth = 2.dp,
                )
            }
            Text(if (isEditMode) "Save" else "Create")
        }
    }
}

/**
 * Header plus a centered [content] slot, used for the loading and "not found" states.
 */
@Composable
private fun EditorPlaceholder(
    isEditMode: Boolean,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        EditorHeader(
            isEditMode = isEditMode,
            saveEnabled = false,
            isSaving = false,
            onBack = onBack,
            onSave = {},
        )
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            content()
        }
    }
}

/**
 * Load state for the edited strategy: distinguishes "still loading" from "loaded" (where the
 * strategy may be null if the id no longer resolves).
 */
private sealed interface StrategyLoad {
    data object Loading : StrategyLoad

    data class Loaded(
        val strategy: CsvImportStrategy?,
    ) : StrategyLoad
}
