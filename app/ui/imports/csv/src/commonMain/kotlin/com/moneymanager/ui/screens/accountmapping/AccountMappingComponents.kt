@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.moneymanager.ui.screens.accountmapping

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.accountmapping.AccountMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import com.moneymanager.importengineapi.createAccountMapping
import com.moneymanager.importengineapi.updateAccountMapping
import com.moneymanager.ui.LocalImportEngine
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import kotlinx.coroutines.launch

/**
 * Collapsible list of account mappings with add/edit/delete actions. Reused by the strategy editor
 * (strategy-scoped mappings) and, implicitly, mirrors the global Account Mappings screen's list.
 */
@Composable
internal fun AccountMappingsSection(
    mappings: List<AccountMapping>,
    accounts: List<Account>,
    enabled: Boolean,
    onEditMapping: (AccountMapping) -> Unit,
    onDeleteMapping: (AccountMapping) -> Unit,
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
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                mappings.sortedBy { it.valuePattern.pattern.lowercase() }.forEach { mapping ->
                    AccountMappingRow(
                        mapping = mapping,
                        accountName = accountsById[mapping.accountId]?.name ?: "Unknown Account",
                        onEdit = { onEditMapping(mapping) },
                        onDelete = { onDeleteMapping(mapping) },
                        enabled = enabled,
                    )
                }
                TextButton(onClick = onAddMapping, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Icon(imageVector = Icons.Filled.Add, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
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
internal fun AccountMappingRow(
    mapping: AccountMapping,
    accountName: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    enabled: Boolean = true,
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
                    text = "→ $accountName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onEdit, enabled = enabled) {
                Icon(imageVector = Icons.Filled.Edit, contentDescription = "Edit", tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onDelete, enabled = enabled) {
                Icon(imageVector = Icons.Filled.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/**
 * Dialog for creating or editing an account mapping. On create, the mapping is scoped to [strategyId]
 * (null = global). On edit, the existing mapping's own scope is preserved.
 */
@Composable
internal fun AccountMappingEditorDialog(
    existingMapping: AccountMapping?,
    accounts: List<Account>,
    strategyId: CsvImportStrategyId?,
    onDismiss: () -> Unit,
) {
    val importEngine = LocalImportEngine.current
    val isEditMode = existingMapping != null
    var columnName by remember { mutableStateOf(existingMapping?.columnName.orEmpty()) }
    var patternText by remember { mutableStateOf(existingMapping?.valuePattern?.pattern.orEmpty()) }
    var selectedAccountId by remember { mutableStateOf(existingMapping?.accountId) }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberSchemaAwareCoroutineScope()

    val isFormValid = columnName.isNotBlank() && patternText.isNotBlank() && selectedAccountId != null

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text(if (isEditMode) "Edit Account Mapping" else "Add Account Mapping") },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
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
                                if (existingMapping != null) {
                                    importEngine.updateAccountMapping(
                                        existingMapping.copy(
                                            columnName = columnName,
                                            valuePattern = pattern,
                                            accountId = accountId,
                                        ),
                                    )
                                } else {
                                    importEngine.createAccountMapping(
                                        columnName = columnName,
                                        valuePattern = pattern,
                                        accountId = accountId,
                                        strategyId = strategyId,
                                    )
                                }
                                onDismiss()
                            } catch (expected: IllegalArgumentException) {
                                errorMessage = "Invalid pattern: ${expected.message}"
                                isSaving = false
                            } catch (expected: Exception) {
                                // Any engine/DB failure must also reset isSaving, else the dialog (whose
                                // dismiss + onDismissRequest are gated on !isSaving) becomes unclosable.
                                errorMessage = "Failed to save mapping: ${expected.message}"
                                isSaving = false
                            }
                        }
                    }
                },
                enabled = isFormValid && !isSaving,
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                }
                Text(if (isEditMode) "Save" else "Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) { Text("Cancel") }
        },
    )
}

/**
 * Simple dropdown for selecting an account.
 */
@Composable
internal fun AccountDropdown(
    accounts: List<Account>,
    selectedAccountId: AccountId?,
    onAccountSelected: (AccountId) -> Unit,
    enabled: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedAccount = accounts.find { it.id == selectedAccountId }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }) {
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
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
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
