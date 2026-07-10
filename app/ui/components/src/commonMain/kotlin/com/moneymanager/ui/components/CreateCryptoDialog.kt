@file:OptIn(ExperimentalMaterial3Api::class)

package com.moneymanager.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.CryptoId
import com.moneymanager.domain.model.CryptoRegistry
import com.moneymanager.importengineapi.createCrypto
import com.moneymanager.ui.LocalImportEngine
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.util.onEnterKeyDown
import kotlinx.coroutines.launch
import org.lighthousegames.logging.logging

private val cryptoDialogLogger = logging()

/**
 * A dialog for manually creating a crypto asset. The ticker drives a [CryptoRegistry] lookup that
 * pre-fills the name for known coins (BTC, ETH, …); it stays editable so unknown/custom assets can
 * be added. Every crypto asset is created at the fixed 18-decimal precision.
 *
 * @param onCryptoCreated Callback invoked with the new crypto id once created.
 * @param onDismiss Callback invoked when the dialog is dismissed.
 */
@Composable
fun CreateCryptoDialog(
    onCryptoCreated: (CryptoId) -> Unit,
    onDismiss: () -> Unit,
) {
    val importEngine = LocalImportEngine.current
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var nameEdited by remember { mutableStateOf(false) }
    val saveState = rememberDialogSaveState()
    val scope = rememberSchemaAwareCoroutineScope()

    val codeFocusRequester = remember { FocusRequester() }
    var codeError by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { runCatching { codeFocusRequester.requestFocus() } }

    val submit: () -> Unit = submit@{
        if (saveState.isSaving) return@submit
        codeError = code.isBlank()
        if (codeError) {
            saveState.errorMessage = "Ticker is required"
        } else {
            saveState.isSaving = true
            saveState.errorMessage = null
            scope.launch {
                try {
                    val cryptoId =
                        importEngine.createCrypto(
                            code = code.trim(),
                            name = name.trim().ifBlank { null },
                        )
                    onCryptoCreated(cryptoId)
                } catch (expected: Exception) {
                    cryptoDialogLogger.error(expected) { "Failed to create crypto asset: ${expected.message}" }
                    saveState.errorMessage = "Failed to create crypto asset: ${expected.message}"
                    saveState.isSaving = false
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!saveState.isSaving) onDismiss() },
        title = { Text("Create Crypto Asset") },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .onEnterKeyDown(submit),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = code,
                    onValueChange = {
                        code = it.uppercase().take(10)
                        codeError = false
                        // Pre-fill the name from the registry for a known ticker (unless the user typed a name).
                        CryptoRegistry.lookup(code)?.let { entry ->
                            if (!nameEdited) name = entry.name
                        }
                    },
                    label = { Text("Ticker (e.g., BTC)") },
                    modifier = Modifier.fillMaxWidth().focusRequester(codeFocusRequester).onEnterKeyDown(submit),
                    singleLine = true,
                    enabled = !saveState.isSaving,
                    isError = codeError,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        nameEdited = true
                    },
                    label = { Text("Name (e.g., Bitcoin)") },
                    modifier = Modifier.fillMaxWidth().onEnterKeyDown(submit),
                    singleLine = true,
                    enabled = !saveState.isSaving,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )
                saveState.errorMessage?.let { error -> ErrorMessageText(error) }
            }
        },
        confirmButton = {
            LoadingTextButton(
                onClick = submit,
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
