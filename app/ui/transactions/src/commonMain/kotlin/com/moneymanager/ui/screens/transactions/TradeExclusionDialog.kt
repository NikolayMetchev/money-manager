package com.moneymanager.ui.screens.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.repository.TradeReadRepository
import com.moneymanager.importengineapi.setTradeExcluded
import com.moneymanager.ui.error.rememberFlowAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.foundation.LocalImportEngine
import com.moneymanager.ui.util.displayDateTime
import com.moneymanager.ui.util.formatAmount
import kotlinx.coroutines.launch

/**
 * Edits whether a trade counts towards balances. A trade's amounts come from its source and aren't
 * edited by hand, so exclusion (with its reason, e.g. "deleted in Koinly") is all this offers — the
 * trade counterpart of the transfer dialog's "Excluded from balances" toggle.
 */
@Composable
fun TradeExclusionDialog(
    tradeId: TradeId,
    tradeRepository: TradeReadRepository,
    onDismiss: () -> Unit,
    onSaved: () -> Unit = {},
) {
    val trade by rememberFlowAsStateWithSchemaErrorHandling(tradeId, initial = null) { tradeRepository.getTradeById(tradeId) }
    val attributes by rememberFlowAsStateWithSchemaErrorHandling(tradeId, initial = null) { tradeRepository.getAttributes(tradeId) }
    var isExcluded by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberSchemaAwareCoroutineScope()
    val importEngine = LocalImportEngine.current

    // Seed the form once the stored exclusion has loaded.
    LaunchedEffect(attributes) {
        val stored = attributes?.firstOrNull { it.attributeType.id.id == WellKnownIds.EXCLUDED_ATTR_TYPE_ID }
        isExcluded = stored != null
        reason = stored?.value.orEmpty()
    }

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text("Trade") },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                trade?.let {
                    Text("${it.timestamp.displayDateTime()}  ${it.description}", style = MaterialTheme.typography.bodyMedium)
                    Text("${formatAmount(it.from)} → ${formatAmount(it.to)}", style = MaterialTheme.typography.bodyMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isExcluded, onCheckedChange = { isExcluded = it }, enabled = !isSaving)
                    Text("Excluded from balances", style = MaterialTheme.typography.bodyMedium)
                }
                if (isExcluded) {
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        label = { Text("Reason") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !isSaving,
                    )
                }
                errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isSaving && attributes != null,
                onClick = {
                    isSaving = true
                    scope.launch {
                        runCatching {
                            importEngine.setTradeExcluded(tradeId, if (isExcluded) reason.trim() else null)
                        }.onSuccess {
                            onSaved()
                            onDismiss()
                        }.onFailure {
                            errorMessage = "Failed to save: ${it.message}"
                            isSaving = false
                        }
                    }
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isSaving) { Text("Cancel") } },
    )
}
