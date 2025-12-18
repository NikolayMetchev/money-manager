@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.moneymanager.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import kotlinx.coroutines.launch
import org.lighthousegames.logging.logging

private val logger = logging()

/**
 * A dialog for creating a new currency.
 *
 * @param currencyRepository Repository to create currencies
 * @param onCurrencyCreated Callback invoked when a currency is created, with the new currency ID
 * @param onDismiss Callback invoked when the dialog is dismissed
 */
@Composable
fun CreateCurrencyDialog(
    currencyRepository: CurrencyRepository,
    onCurrencyCreated: (CurrencyId) -> Unit,
    onDismiss: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    val scope = rememberSchemaAwareCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text("Create New Currency") },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.uppercase().take(3) },
                    label = { Text("Currency Code (e.g., USD)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isSaving,
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Currency Name (e.g., US Dollar)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isSaving,
                )

                errorMessage?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when {
                        code.isBlank() -> errorMessage = "Currency code is required"
                        code.length != 3 -> errorMessage = "Currency code must be 3 characters"
                        name.isBlank() -> errorMessage = "Currency name is required"
                        else -> {
                            isSaving = true
                            errorMessage = null
                            scope.launch {
                                try {
                                    val currencyId = currencyRepository.upsertCurrencyByCode(code.trim(), name.trim())
                                    onCurrencyCreated(currencyId)
                                } catch (e: Exception) {
                                    logger.error(e) { "Failed to create currency: ${e.message}" }
                                    errorMessage = "Failed to create currency: ${e.message}"
                                    isSaving = false
                                }
                            }
                        }
                    }
                },
                enabled = !isSaving,
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Create")
                }
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
