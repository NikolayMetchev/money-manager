@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.moneymanager.ui.screens.accountmapping

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.moneymanager.compose.filepicker.rememberFilePicker
import com.moneymanager.compose.filepicker.rememberFileSaver
import com.moneymanager.compose.scrollbar.VerticalScrollbarForLazyList
import com.moneymanager.database.json.AccountMappingExportCodec
import com.moneymanager.database.service.AccountMappingExportService
import com.moneymanager.database.service.AccountMappingParseResult
import com.moneymanager.database.service.Resolution
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.accountmapping.AccountMapping
import com.moneymanager.domain.repository.AccountMappingReadRepository
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.importengineapi.deleteAccountMapping
import com.moneymanager.ui.LocalImportEngine
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import kotlinx.coroutines.launch

/**
 * Global account-mappings screen: lists, adds, edits, deletes, and exports/imports only the **global**
 * account mappings (those with no strategy). Strategy-specific mappings are edited in each strategy's
 * editor instead.
 */
@Composable
fun AccountMappingsScreen(
    accountMappingRepository: AccountMappingReadRepository,
    accountRepository: AccountReadRepository,
    accountMappingExportService: AccountMappingExportService,
    appVersion: AppVersion,
    onBack: () -> Unit = {},
) {
    val importEngine = LocalImportEngine.current
    val scope = rememberSchemaAwareCoroutineScope()

    val allMappings by accountMappingRepository.getAllMappings().collectAsStateWithSchemaErrorHandling(emptyList())
    // This screen manages only global mappings; strategy-scoped ones are edited in the strategy editor.
    val mappings = remember(allMappings) { allMappings.filter { it.strategyId == null } }
    val accounts by accountRepository.getAllAccounts().collectAsStateWithSchemaErrorHandling(emptyList())
    val accountsById = remember(accounts) { accounts.associateBy { it.id } }

    var editingMapping by remember { mutableStateOf<AccountMapping?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var parseResult by remember { mutableStateOf<AccountMappingParseResult?>(null) }

    val fileSaver = rememberFileSaver(onResult = {})

    val filePicker =
        rememberFilePicker(
            mimeTypes = listOf("application/json"),
            onResult = { result ->
                if (result != null) {
                    scope.launch {
                        @Suppress("TooGenericExceptionCaught")
                        try {
                            val export = AccountMappingExportCodec.decode(result.content)
                            val parsed = accountMappingExportService.parseExport(export)
                            errorMessage = null
                            if (parsed.unresolvedAccountNames.isEmpty()) {
                                accountMappingExportService.importMappings(export, emptyMap())
                            } else {
                                parseResult = parsed
                            }
                        } catch (expected: Exception) {
                            errorMessage = "Failed to import: ${expected.message}"
                        }
                    }
                }
            },
        )

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(onClick = onBack) {
                        Text("<", style = MaterialTheme.typography.titleLarge)
                    }
                    Text(text = "Account Mappings", style = MaterialTheme.typography.headlineMedium)
                }
                Row {
                    IconButton(
                        onClick = {
                            scope.launch {
                                @Suppress("TooGenericExceptionCaught")
                                try {
                                    errorMessage = null
                                    val export = accountMappingExportService.toExport(mappings, appVersion)
                                    val json = AccountMappingExportCodec.encode(export)
                                    fileSaver.launch("account-mappings.json", json)
                                } catch (expected: Exception) {
                                    errorMessage = "Failed to export: ${expected.message}"
                                }
                            }
                        },
                    ) {
                        Icon(imageVector = Icons.Filled.Share, contentDescription = "Export")
                    }
                    IconButton(onClick = { filePicker.launch() }) {
                        Text(text = "⭳", style = MaterialTheme.typography.titleLarge)
                    }
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(imageVector = Icons.Filled.Add, contentDescription = "Add mapping")
                    }
                }
            }

            errorMessage?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            if (mappings.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No global account mappings yet.\nThese route CSV/QIF values to accounts across all strategies.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                val lazyListState = rememberLazyListState()
                Box(modifier = Modifier.weight(1f)) {
                    LazyColumn(state = lazyListState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(mappings.sortedBy { it.valuePattern.pattern.lowercase() }) { mapping ->
                            AccountMappingRow(
                                mapping = mapping,
                                accountName = accountsById[mapping.accountId]?.name ?: "Unknown Account",
                                onEdit = { editingMapping = mapping },
                                onDelete = {
                                    scope.launch {
                                        @Suppress("TooGenericExceptionCaught")
                                        try {
                                            importEngine.deleteAccountMapping(mapping.id)
                                        } catch (expected: Exception) {
                                            errorMessage = "Failed to delete mapping: ${expected.message}"
                                        }
                                    }
                                },
                            )
                        }
                    }
                    VerticalScrollbarForLazyList(
                        lazyListState = lazyListState,
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    )
                }
            }
        }
    }

    editingMapping?.let { mapping ->
        AccountMappingEditorDialog(
            existingMapping = mapping,
            accounts = accounts,
            strategyId = null,
            onDismiss = { editingMapping = null },
        )
    }

    if (showAddDialog) {
        AccountMappingEditorDialog(
            existingMapping = null,
            accounts = accounts,
            strategyId = null,
            onDismiss = { showAddDialog = false },
        )
    }

    parseResult?.let { parsed ->
        ImportAccountMappingsDialog(
            parseResult = parsed,
            accounts = accounts,
            onDismiss = { parseResult = null },
            onImport = { resolutions ->
                scope.launch {
                    @Suppress("TooGenericExceptionCaught")
                    try {
                        accountMappingExportService.importMappings(parsed.export, resolutions)
                        parseResult = null
                    } catch (expected: Exception) {
                        errorMessage = "Failed to import: ${expected.message}"
                        parseResult = null
                    }
                }
            },
        )
    }
}

/**
 * Dialog for resolving account names in an imported mappings file that don't exist in this database.
 * Each unresolved name is either created as a new account or mapped to an existing one.
 */
@Composable
private fun ImportAccountMappingsDialog(
    parseResult: AccountMappingParseResult,
    accounts: List<Account>,
    onDismiss: () -> Unit,
    onImport: (Map<String, Resolution>) -> Unit,
) {
    // Default every unresolved name to "create a new account with that name".
    val resolutions =
        remember(parseResult) {
            mutableStateMapOf<String, Resolution>().apply {
                parseResult.unresolvedAccountNames.forEach { name -> put(name, Resolution.CreateNew(name)) }
            }
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Resolve Accounts") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "These accounts referenced by the file don't exist yet. Create them, or map each to an existing account.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                parseResult.unresolvedAccountNames.forEach { name ->
                    val resolution = resolutions[name]
                    Column {
                        Text(name, style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(4.dp))
                        AccountResolutionDropdown(
                            accountName = name,
                            accounts = accounts,
                            resolution = resolution,
                            onResolutionChanged = { resolutions[name] = it },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onImport(resolutions.toMap()) }) { Text("Import") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun AccountResolutionDropdown(
    accountName: String,
    accounts: List<Account>,
    resolution: Resolution?,
    onResolutionChanged: (Resolution) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label =
        when (resolution) {
            is Resolution.MapToExisting -> accounts.find { it.id.id == resolution.id }?.name ?: "Select..."
            else -> "Create new account \"$accountName\""
        }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Create new account \"$accountName\"") },
                onClick = {
                    onResolutionChanged(Resolution.CreateNew(accountName))
                    expanded = false
                },
            )
            accounts.sortedBy { it.name }.forEach { account ->
                DropdownMenuItem(
                    text = { Text("Map to: ${account.name}") },
                    onClick = {
                        onResolutionChanged(Resolution.MapToExisting(account.id.id))
                        expanded = false
                    },
                )
            }
        }
    }
}
