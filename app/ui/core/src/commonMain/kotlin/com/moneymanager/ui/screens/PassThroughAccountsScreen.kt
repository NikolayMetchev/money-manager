package com.moneymanager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.passthrough.PassThroughAccount
import com.moneymanager.domain.model.passthrough.PassThroughAccountId
import com.moneymanager.domain.model.passthrough.PassThroughRule
import com.moneymanager.domain.repository.PassThroughAccountReadRepository
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.createPassThroughAccount
import com.moneymanager.importengineapi.deletePassThroughAccount
import com.moneymanager.importengineapi.updatePassThroughAccount
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import kotlinx.coroutines.launch

/**
 * Manages pass-through (conduit) account definitions such as Curve — the generic, user-editable config
 * that tells importers how to detect a wrapper-card charge and route it through a conduit account. All
 * writes go through the import engine (the sole DB writer).
 */
@Composable
fun PassThroughAccountsScreen(
    passThroughAccountRepository: PassThroughAccountReadRepository,
    importEngine: ImportEngine,
) {
    val scope = rememberSchemaAwareCoroutineScope()
    val accounts by passThroughAccountRepository.getAll().collectAsStateWithSchemaErrorHandling(emptyList())
    var editing by remember { mutableStateOf<PassThroughAccount?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "A pass-through account (e.g. Curve) is a wrapper card whose charges are forwarded to an " +
                    "underlying card. Matching charges are routed card → conduit → merchant.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            Button(onClick = {
                editing = null
                showEditor = true
            }) {
                Text("Add")
            }
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(accounts, key = { it.id.value }) { account ->
                PassThroughAccountCard(
                    account = account,
                    onToggleEnabled = {
                        scope.launch { importEngine.updatePassThroughAccount(account.copy(enabled = !account.enabled)) }
                    },
                    onEdit = {
                        editing = account
                        showEditor = true
                    },
                    onDelete = { scope.launch { importEngine.deletePassThroughAccount(account.id) } },
                )
            }
        }
    }

    if (showEditor) {
        PassThroughAccountEditorDialog(
            initial = editing,
            onDismiss = { showEditor = false },
            onSave = { account ->
                scope.launch {
                    if (account.id.value == 0L) {
                        importEngine.createPassThroughAccount(account)
                    } else {
                        importEngine.updatePassThroughAccount(account)
                    }
                }
                showEditor = false
            },
        )
    }
}

@Composable
private fun PassThroughAccountCard(
    account: PassThroughAccount,
    onToggleEnabled: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(account.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Conduit: ${account.conduitAccountName} · ${account.rules.size} rule(s)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = account.enabled, onCheckedChange = { onToggleEnabled() })
            TextButton(onClick = onEdit) { Text("Edit") }
            TextButton(onClick = onDelete) { Text("Delete") }
        }
    }
}

@Composable
private fun PassThroughAccountEditorDialog(
    initial: PassThroughAccount?,
    onDismiss: () -> Unit,
    onSave: (PassThroughAccount) -> Unit,
) {
    val firstRule = initial?.rules?.firstOrNull()
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var conduit by remember { mutableStateOf(initial?.conduitAccountName ?: "") }
    var detection by remember { mutableStateOf(firstRule?.detectionPattern ?: "(?i)^CRV\\*") }
    var merchant by remember { mutableStateOf(firstRule?.merchantPattern ?: "(?i)^CRV\\*\\s*(.+?)(?:\\s{2,}.*)?$") }
    var template by remember { mutableStateOf(firstRule?.merchantTemplate ?: "$1") }

    // Validate the patterns here so an invalid regex is caught at save time rather than surfacing much
    // later, buried inside the detector at import time.
    val detectionError = regexError(detection)
    val merchantError = regexError(merchant)
    val canSave =
        name.isNotBlank() &&
            conduit.isNotBlank() &&
            detection.isNotBlank() &&
            detectionError == null &&
            merchantError == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add pass-through account" else "Edit pass-through account") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(conduit, { conduit = it }, label = { Text("Conduit account name") }, singleLine = true)
                OutlinedTextField(
                    detection,
                    { detection = it },
                    label = { Text("Detection regex") },
                    singleLine = true,
                    isError = detectionError != null,
                    supportingText = detectionError?.let { { Text(it) } },
                )
                OutlinedTextField(
                    merchant,
                    { merchant = it },
                    label = { Text("Merchant regex") },
                    singleLine = true,
                    isError = merchantError != null,
                    supportingText = merchantError?.let { { Text(it) } },
                )
                OutlinedTextField(template, { template = it }, label = { Text("Merchant template") }, singleLine = true)
                if (initial != null && initial.rules.size > 1) {
                    Text(
                        "This account has ${initial.rules.size} rules; only the first is editable here. " +
                            "The remaining ${initial.rules.size - 1} are preserved on save.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    val editedFirstRule =
                        PassThroughRule(
                            detectionPattern = detection.trim(),
                            merchantPattern = merchant.trim(),
                            merchantTemplate = template,
                        )
                    // Preserve any additional rules the editor doesn't expose so editing a multi-rule
                    // account doesn't silently drop them.
                    val remainingRules = initial?.rules?.drop(1).orEmpty()
                    onSave(
                        PassThroughAccount(
                            id = initial?.id ?: PassThroughAccountId(0),
                            name = name.trim(),
                            conduitAccountName = conduit.trim(),
                            relationshipTypeId =
                                initial?.relationshipTypeId
                                    ?: WellKnownIds.PASS_THROUGH_RELATIONSHIP_TYPE_ID,
                            enabled = initial?.enabled ?: true,
                            rules = listOf(editedFirstRule) + remainingRules,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Returns a human-readable error if [pattern] is not a valid regex, or null when it compiles. */
private fun regexError(pattern: String): String? =
    if (pattern.isBlank()) {
        null
    } else {
        runCatching { Regex(pattern) }.exceptionOrNull()?.let { "Invalid regex" }
    }
