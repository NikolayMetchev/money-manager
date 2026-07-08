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
import androidx.compose.ui.text.input.KeyboardType
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

/** Highest decimal precision a crypto asset can have (ETH/ERC-20 use 18); bounded so `10^decimals` fits a Long. */
private const val MAX_CRYPTO_DECIMALS = 18

private fun scaleFactorForDecimals(decimals: Int): Long {
    var factor = 1L
    repeat(decimals) { factor *= 10 }
    return factor
}

private fun decimalsForScaleFactor(scaleFactor: Long): Int {
    var decimals = 0
    var value = scaleFactor
    while (value > 1) {
        value /= 10
        decimals++
    }
    return decimals
}

/**
 * A dialog for manually creating a crypto asset. The ticker drives a [CryptoRegistry] lookup that
 * pre-fills the name and decimals for known coins (BTC, ETH, …); both stay editable so unknown/custom
 * assets can be added with any precision.
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
    var decimals by remember { mutableStateOf("8") }
    val saveState = rememberDialogSaveState()
    val scope = rememberSchemaAwareCoroutineScope()

    val codeFocusRequester = remember { FocusRequester() }
    var codeError by remember { mutableStateOf(false) }
    var decimalsError by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { runCatching { codeFocusRequester.requestFocus() } }

    val submit: () -> Unit = submit@{
        if (saveState.isSaving) return@submit
        val decimalsValue = decimals.toIntOrNull()
        codeError = code.isBlank()
        decimalsError = decimalsValue == null || decimalsValue !in 0..MAX_CRYPTO_DECIMALS
        when {
            codeError -> saveState.errorMessage = "Ticker is required"
            decimalsError -> saveState.errorMessage = "Decimals must be between 0 and $MAX_CRYPTO_DECIMALS"
            else -> {
                saveState.isSaving = true
                saveState.errorMessage = null
                scope.launch {
                    try {
                        val cryptoId =
                            importEngine.createCrypto(
                                code = code.trim(),
                                name = name.trim().ifBlank { null },
                                scaleFactor = scaleFactorForDecimals(decimalsValue!!),
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
                        // Pre-fill name/decimals from the registry for a known ticker (unless the user typed a name).
                        CryptoRegistry.lookup(code)?.let { entry ->
                            if (!nameEdited) name = entry.name
                            // Catalog entries may carry no scale factor (name known, decimals unknown);
                            // leave the field's default in that case rather than forcing 8.
                            entry.scaleFactor?.let { decimals = decimalsForScaleFactor(it).toString() }
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
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = decimals,
                    onValueChange = {
                        decimals = it.filter { ch -> ch.isDigit() }.take(2)
                        decimalsError = false
                    },
                    label = { Text("Decimals (e.g., 8 for BTC, 18 for ETH)") },
                    modifier = Modifier.fillMaxWidth().onEnterKeyDown(submit),
                    singleLine = true,
                    enabled = !saveState.isSaving,
                    isError = decimalsError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
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
