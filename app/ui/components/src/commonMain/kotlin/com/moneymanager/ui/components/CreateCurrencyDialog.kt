package com.moneymanager.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.importengineapi.createCurrency
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.foundation.LocalImportEngine
import com.moneymanager.ui.util.onEnterKeyDown
import kotlinx.coroutines.launch
import org.lighthousegames.logging.logging

private val logger = logging()

/**
 * A dialog for creating a new currency.
 *
 * @param onCurrencyCreated Callback invoked when a currency is created, with the new currency ID
 * @param onDismiss Callback invoked when the dialog is dismissed
 */
@Composable
fun CreateCurrencyDialog(
    onCurrencyCreated: (CurrencyId) -> Unit,
    onDismiss: () -> Unit,
) {
    val importEngine = LocalImportEngine.current
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    val saveState = rememberDialogSaveState()

    val scope = rememberSchemaAwareCoroutineScope()

    val codeFocusRequester = remember { FocusRequester() }
    val nameFocusRequester = remember { FocusRequester() }
    var codeError by remember { mutableStateOf(false) }
    var nameError by remember { mutableStateOf(false) }

    // Focus the first field on open so the user can type immediately and Enter has a focused field to
    // route through (the key-event handler only fires while a field inside the dialog is focused).
    LaunchedEffect(Unit) { runCatching { codeFocusRequester.requestFocus() } }

    val submit: () -> Unit = submit@{
        if (saveState.isSaving) return@submit
        // Flag every invalid required field so they all highlight at once (not just the first).
        codeError = code.isBlank() || code.length != 3
        nameError = name.isBlank()
        when {
            codeError || nameError -> {
                saveState.errorMessage =
                    when {
                        code.isBlank() -> "Currency code is required"
                        code.length != 3 -> "Currency code must be 3 characters"
                        else -> "Currency name is required"
                    }
                // Move the cursor to the first invalid field; the rest stay highlighted via their flags.
                when {
                    codeError -> codeFocusRequester.requestFocus()
                    else -> nameFocusRequester.requestFocus()
                }
            }
            else -> {
                saveState.isSaving = true
                saveState.errorMessage = null
                scope.launch {
                    try {
                        val currencyId = importEngine.createCurrency(code.trim(), name.trim())
                        onCurrencyCreated(currencyId)
                    } catch (expected: Exception) {
                        logger.error(expected) { "Failed to create currency: ${expected.message}" }
                        saveState.errorMessage = "Failed to create currency: ${expected.message}"
                        saveState.isSaving = false
                    }
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!saveState.isSaving) onDismiss() },
        title = { Text("Create New Currency") },
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
                        code = it.uppercase().take(3)
                        codeError = false
                    },
                    label = { Text("Currency Code (e.g., USD)") },
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
                        nameError = false
                    },
                    label = { Text("Currency Name (e.g., US Dollar)") },
                    modifier = Modifier.fillMaxWidth().focusRequester(nameFocusRequester).onEnterKeyDown(submit),
                    singleLine = true,
                    enabled = !saveState.isSaving,
                    isError = nameError,
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
