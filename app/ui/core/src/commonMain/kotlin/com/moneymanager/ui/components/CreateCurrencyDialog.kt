@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.moneymanager.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
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
import com.moneymanager.ui.LocalDeviceId
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.manualProvenance
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
    val saveState = rememberDialogSaveState()
    val provenance = manualProvenance(LocalDeviceId.current)

    val scope = rememberSchemaAwareCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!saveState.isSaving) onDismiss() },
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
                    enabled = !saveState.isSaving,
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Currency Name (e.g., US Dollar)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !saveState.isSaving,
                )

                saveState.errorMessage?.let { error -> ErrorMessageText(error) }
            }
        },
        confirmButton = {
            LoadingTextButton(
                onClick = {
                    when {
                        code.isBlank() -> saveState.errorMessage = "Currency code is required"
                        code.length != 3 -> saveState.errorMessage = "Currency code must be 3 characters"
                        name.isBlank() -> saveState.errorMessage = "Currency name is required"
                        else -> {
                            saveState.isSaving = true
                            saveState.errorMessage = null
                            scope.launch {
                                try {
                                    val currencyId = currencyRepository.upsertCurrencyByCode(code.trim(), name.trim(), provenance)
                                    onCurrencyCreated(currencyId)
                                } catch (expected: Exception) {
                                    logger.error(expected) { "Failed to create currency: ${expected.message}" }
                                    saveState.errorMessage = "Failed to create currency: ${expected.message}"
                                    saveState.isSaving = false
                                }
                            }
                        }
                    }
                },
                enabled = !saveState.isSaving,
                loading = saveState.isSaving,
                label = "Create",
            )
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !saveState.isSaving,
            ) {
                Text("Cancel")
            }
        },
    )
}
