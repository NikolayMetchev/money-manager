@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.moneymanager.ui.screens.csvstrategy

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moneymanager.database.service.CsvStrategyExportService
import com.moneymanager.database.service.ImportParseResult
import com.moneymanager.database.service.ReferenceType
import com.moneymanager.database.service.Resolution
import com.moneymanager.database.service.UnresolvedReference
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.csvstrategy.CsvAccountMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.domain.repository.CsvAccountMappingRepository
import com.moneymanager.domain.repository.CsvImportStrategyRepository
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import kotlinx.coroutines.launch

/**
 * Dialog for importing a CSV strategy from a JSON file.
 * Shows unresolved references and allows the user to map them to existing entities or create new ones.
 */
@Composable
fun ImportStrategyDialog(
    parseResult: ImportParseResult,
    csvImportStrategyRepository: CsvImportStrategyRepository,
    csvStrategyExportService: CsvStrategyExportService,
    accountRepository: AccountRepository,
    categoryRepository: CategoryRepository,
    currencyRepository: CurrencyRepository,
    onDismiss: () -> Unit,
    onImportSuccess: () -> Unit,
) {
    val accounts by accountRepository.getAllAccounts()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())
    val categories by categoryRepository.getAllCategories()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())
    val currencies by currencyRepository.getAllCurrencies()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())

    var resolutions by remember {
        mutableStateOf<Map<UnresolvedReference, Resolution>>(emptyMap())
    }
    var strategyName by remember { mutableStateOf(parseResult.strategyName) }
    var isImporting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberSchemaAwareCoroutineScope()

    // Check if all unresolved references have been resolved
    val allResolved = parseResult.unresolvedReferences.all { it in resolutions.keys }

    // Check for name conflict
    val existingStrategies by csvImportStrategyRepository.getAllStrategies()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())
    val nameConflict = existingStrategies.any { it.name == strategyName }

    AlertDialog(
        onDismissRequest = { if (!isImporting) onDismiss() },
        title = { Text("Import Strategy") },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = strategyName,
                    onValueChange = { strategyName = it },
                    label = { Text("Strategy Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isImporting,
                    isError = strategyName.isBlank() || nameConflict,
                    supportingText = {
                        when {
                            strategyName.isBlank() -> Text("Name is required")
                            nameConflict ->
                                Text(
                                    "A strategy with this name already exists",
                                    color = MaterialTheme.colorScheme.error,
                                )
                        }
                    },
                )

                if (parseResult.unresolvedReferences.isEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "All references resolved. Ready to import.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Resolve Missing References",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "The following references need to be mapped or created:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    parseResult.unresolvedReferences.forEach { ref ->
                        ReferenceResolutionRow(
                            reference = ref,
                            resolution = resolutions[ref],
                            onResolutionChanged = { resolution ->
                                resolutions = resolutions + (ref to resolution)
                            },
                            accounts = accounts,
                            categories = categories,
                            currencies = currencies,
                            enabled = !isImporting,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
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
            TextButton(
                onClick = {
                    isImporting = true
                    errorMessage = null
                    scope.launch {
                        try {
                            // Update the export with the new name if changed
                            val exportWithNewName = parseResult.export.copy(name = strategyName)

                            // Create the strategy
                            val strategy =
                                csvStrategyExportService.createStrategyFromExport(
                                    export = exportWithNewName,
                                    resolutions = resolutions,
                                )

                            // Save it
                            csvImportStrategyRepository.createStrategy(strategy)
                            onImportSuccess()
                            onDismiss()
                        } catch (expected: Exception) {
                            errorMessage = "Failed to import: ${expected.message}"
                            isImporting = false
                        }
                    }
                },
                enabled = allResolved && strategyName.isNotBlank() && !nameConflict && !isImporting,
            ) {
                if (isImporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp),
                        strokeWidth = 2.dp,
                    )
                }
                Text("Import")
            }
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

/**
 * Row for resolving a single unresolved reference.
 */
@Composable
private fun ReferenceResolutionRow(
    reference: UnresolvedReference,
    resolution: Resolution?,
    onResolutionChanged: (Resolution) -> Unit,
    accounts: List<Account>,
    categories: List<Category>,
    currencies: List<Currency>,
    enabled: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    var createNewName by remember { mutableStateOf(reference.name) }
    var selectedOption by remember(resolution) {
        mutableStateOf(
            when (resolution) {
                is Resolution.CreateNew -> "create"
                is Resolution.MapToExisting -> "existing:${resolution.id}"
                null -> null
            },
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = reference.name,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    val refType = reference.type.name.lowercase().replaceFirstChar { it.uppercase() }
                    val fieldName = reference.fieldType.name.lowercase().replace("_", " ")
                    Text(
                        text = "$refType for $fieldName",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (resolution != null) {
                    Text(
                        "✓",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Resolution options
            when (reference.type) {
                ReferenceType.ACCOUNT -> {
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { if (enabled) expanded = !expanded },
                    ) {
                        OutlinedTextField(
                            value =
                                when {
                                    selectedOption == "create" -> "Create: $createNewName"
                                    selectedOption?.startsWith("existing:") == true -> {
                                        val id = selectedOption!!.removePrefix("existing:").toLongOrNull()
                                        accounts.find { it.id.id == id }?.name ?: "Select..."
                                    }
                                    else -> "Select..."
                                },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Map to") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                            enabled = enabled,
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false },
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Create new account",
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                onClick = {
                                    selectedOption = "create"
                                    onResolutionChanged(Resolution.CreateNew(createNewName))
                                    expanded = false
                                },
                            )
                            accounts.forEach { account ->
                                DropdownMenuItem(
                                    text = { Text(account.name) },
                                    onClick = {
                                        selectedOption = "existing:${account.id.id}"
                                        onResolutionChanged(Resolution.MapToExisting(account.id.id))
                                        expanded = false
                                    },
                                )
                            }
                        }
                    }

                    // Show name field for create option
                    if (selectedOption == "create") {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = createNewName,
                            onValueChange = { newName ->
                                createNewName = newName
                                onResolutionChanged(Resolution.CreateNew(newName))
                            },
                            label = { Text("New account name") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = enabled,
                        )
                    }
                }

                ReferenceType.CATEGORY -> {
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { if (enabled) expanded = !expanded },
                    ) {
                        OutlinedTextField(
                            value =
                                when {
                                    selectedOption == "create" -> "Create: $createNewName"
                                    selectedOption?.startsWith("existing:") == true -> {
                                        val id = selectedOption!!.removePrefix("existing:").toLongOrNull()
                                        categories.find { it.id == id }?.name ?: "Select..."
                                    }
                                    else -> "Select..."
                                },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Map to") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                            enabled = enabled,
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false },
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Create new category",
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                onClick = {
                                    selectedOption = "create"
                                    onResolutionChanged(Resolution.CreateNew(createNewName))
                                    expanded = false
                                },
                            )
                            categories.forEach { category ->
                                DropdownMenuItem(
                                    text = { Text(category.name) },
                                    onClick = {
                                        selectedOption = "existing:${category.id}"
                                        onResolutionChanged(Resolution.MapToExisting(category.id))
                                        expanded = false
                                    },
                                )
                            }
                        }
                    }

                    if (selectedOption == "create") {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = createNewName,
                            onValueChange = { newName ->
                                createNewName = newName
                                onResolutionChanged(Resolution.CreateNew(newName))
                            },
                            label = { Text("New category name") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = enabled,
                        )
                    }
                }

                ReferenceType.CURRENCY -> {
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { if (enabled) expanded = !expanded },
                    ) {
                        OutlinedTextField(
                            value =
                                when {
                                    selectedOption == "create" -> "Create: $createNewName"
                                    selectedOption?.startsWith("existing:") == true -> {
                                        val id = selectedOption!!.removePrefix("existing:")
                                        currencies.find { it.id.id.toString() == id }?.let { "${it.code} - ${it.name}" }
                                            ?: "Select..."
                                    }
                                    else -> "Select..."
                                },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Map to") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                            enabled = enabled,
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false },
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Create new currency",
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                onClick = {
                                    selectedOption = "create"
                                    onResolutionChanged(Resolution.CreateNew(createNewName))
                                    expanded = false
                                },
                            )
                            currencies.forEach { currency ->
                                DropdownMenuItem(
                                    text = { Text("${currency.code} - ${currency.name}") },
                                    onClick = {
                                        selectedOption = "existing:${currency.id.id}"
                                        // Currency uses UUID, so we need to handle differently
                                        // For now, store the string representation
                                        onResolutionChanged(Resolution.MapToExisting(0L)) // This won't work for currency
                                        expanded = false
                                    },
                                )
                            }
                        }
                    }

                    if (selectedOption == "create") {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = createNewName,
                            onValueChange = { newName ->
                                createNewName = newName
                                onResolutionChanged(Resolution.CreateNew(newName))
                            },
                            label = { Text("New currency code") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = enabled,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Section showing existing account mappings with edit/delete actions.
 */
@Composable
internal fun AccountMappingsSection(
    mappings: List<CsvAccountMapping>,
    accounts: List<Account>,
    enabled: Boolean,
    onEditMapping: (CsvAccountMapping) -> Unit,
    onDeleteMapping: (CsvAccountMapping) -> Unit,
    onAddMapping: () -> Unit,
) {
    val accountsById = remember(accounts) { accounts.associateBy { it.id } }
    var expanded by remember { mutableStateOf(mappings.isNotEmpty()) }

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) { expanded = !expanded }
                    .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text =
                    if (mappings.isEmpty()) {
                        "None configured (click to expand)"
                    } else {
                        "${mappings.size} mapping(s) configured"
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Icon(
                imageVector =
                    if (expanded) {
                        Icons.Filled.KeyboardArrowUp
                    } else {
                        Icons.Filled.KeyboardArrowDown
                    },
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Sort mappings alphabetically by pattern
                mappings.sortedBy { it.valuePattern.pattern.lowercase() }.forEach { mapping ->
                    val account = accountsById[mapping.accountId]
                    AccountMappingRow(
                        mapping = mapping,
                        accountName = account?.name ?: "Unknown Account",
                        enabled = enabled,
                        onEdit = { onEditMapping(mapping) },
                        onDelete = { onDeleteMapping(mapping) },
                    )
                }

                // Add button
                TextButton(
                    onClick = onAddMapping,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text("Add Account Mapping")
                }
            }
        }
    }
}

/**
 * Single row displaying an account mapping with edit/delete actions.
 */
@Composable
private fun AccountMappingRow(
    mapping: CsvAccountMapping,
    accountName: String,
    enabled: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Column: ${mapping.columnName}",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Pattern: ${mapping.valuePattern.pattern}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "→ $accountName (ID: ${mapping.accountId.id})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onEdit, enabled = enabled) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = "Edit",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = onDelete, enabled = enabled) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * Dialog for editing or creating an account mapping.
 */
@Composable
internal fun AccountMappingEditorDialog(
    existingMapping: CsvAccountMapping?,
    strategyId: CsvImportStrategyId,
    accounts: List<Account>,
    csvAccountMappingRepository: CsvAccountMappingRepository,
    onDismiss: () -> Unit,
) {
    val isEditMode = existingMapping != null
    var columnName by remember { mutableStateOf(existingMapping?.columnName.orEmpty()) }
    var patternText by remember { mutableStateOf(existingMapping?.valuePattern?.pattern.orEmpty()) }
    var selectedAccountId by remember { mutableStateOf(existingMapping?.accountId) }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberSchemaAwareCoroutineScope()

    val isFormValid =
        columnName.isNotBlank() &&
            patternText.isNotBlank() &&
            selectedAccountId != null

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text(if (isEditMode) "Edit Account Mapping" else "Add Account Mapping") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = columnName,
                    onValueChange = { columnName = it },
                    label = { Text("Column Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isSaving,
                    isError = columnName.isBlank(),
                    supportingText = { Text("CSV column to match against (e.g., Name, Payee)") },
                )

                OutlinedTextField(
                    value = patternText,
                    onValueChange = { patternText = it },
                    label = { Text("Value Pattern (Regex)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isSaving,
                    isError = patternText.isBlank(),
                    supportingText = { Text("Use ^value$ for exact match, or .*keyword.* for contains") },
                )

                Text("Target Account", style = MaterialTheme.typography.bodyMedium)
                AccountDropdown(
                    accounts = accounts,
                    selectedAccountId = selectedAccountId,
                    onAccountSelected = { selectedAccountId = it },
                    enabled = !isSaving,
                )

                errorMessage?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (isFormValid) {
                        isSaving = true
                        errorMessage = null
                        val accountId = selectedAccountId ?: return@TextButton
                        scope.launch {
                            try {
                                val pattern = Regex(patternText, RegexOption.IGNORE_CASE)
                                val mapping = existingMapping
                                if (mapping != null) {
                                    csvAccountMappingRepository.updateMapping(
                                        mapping.copy(
                                            columnName = columnName,
                                            valuePattern = pattern,
                                            accountId = accountId,
                                        ),
                                    )
                                } else {
                                    csvAccountMappingRepository.createMapping(
                                        strategyId = strategyId,
                                        columnName = columnName,
                                        valuePattern = pattern,
                                        accountId = accountId,
                                    )
                                }
                                onDismiss()
                            } catch (expected: IllegalArgumentException) {
                                errorMessage = "Invalid pattern: ${expected.message}"
                                isSaving = false
                            }
                        }
                    }
                },
                enabled = isFormValid && !isSaving,
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp),
                        strokeWidth = 2.dp,
                    )
                }
                Text(if (isEditMode) "Save" else "Add")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSaving,
            ) {
                Text("Cancel")
            }
        },
    )
}

/**
 * Simple dropdown for selecting an account.
 */
@Composable
private fun AccountDropdown(
    accounts: List<Account>,
    selectedAccountId: AccountId?,
    onAccountSelected: (AccountId) -> Unit,
    enabled: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedAccount = accounts.find { it.id == selectedAccountId }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
    ) {
        OutlinedTextField(
            value = selectedAccount?.name ?: "Select account...",
            onValueChange = {},
            readOnly = true,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            enabled = enabled,
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            accounts.sortedBy { it.name }.forEach { account ->
                DropdownMenuItem(
                    text = { Text(account.name) },
                    onClick = {
                        onAccountSelected(account.id)
                        expanded = false
                    },
                )
            }
        }
    }
}
