@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.moneymanager.ui.screens.apistrategy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.apiimporter.ApiCounterpartySuggestion
import com.moneymanager.domain.model.ApiCredentialId
import com.moneymanager.domain.model.ApiSession
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import kotlinx.coroutines.CompletableDeferred

// [answer] is set when a bulk run asked and waits for the names (null: leave the session for later).
internal class PendingApiImport(
    val session: ApiSession,
    val strategy: ApiImportStrategy,
    val counterparties: List<ApiCounterpartySuggestion>,
    val answer: CompletableDeferred<Map<String, String>?>? = null,
)

internal data class PendingApiReimport(
    val session: ApiSession,
    val strategy: ApiImportStrategy,
)

internal data class PendingApiReimportAll(
    val credentialId: ApiCredentialId,
    val strategy: ApiImportStrategy,
    val importedSessionCount: Int,
)

@Composable
internal fun CounterpartyConfirmationDialog(
    counterparties: List<ApiCounterpartySuggestion>,
    onDismiss: () -> Unit,
    onConfirm: (Map<String, String>) -> Unit,
) {
    var names by remember(counterparties) {
        mutableStateOf(counterparties.map { it.suggestedAccountName })
    }
    val hasBlankName = names.any { it.isBlank() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirm Counterparties") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "These counterparties will be created before importing transactions.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(counterparties) { index, suggestion ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedTextField(
                                value = names[index],
                                onValueChange = { value ->
                                    names = names.toMutableList().also { it[index] = value }
                                },
                                label = { Text("Account name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                text = "Counterparty ID: ${suggestion.counterpartyId}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "Downloaded names: ${suggestion.downloadedNames.joinToString()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !hasBlankName,
                onClick = {
                    onConfirm(counterparties.mapIndexed { index, suggestion -> suggestion.counterpartyId to names[index].trim() }.toMap())
                },
            ) {
                Text("Create and Import")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
