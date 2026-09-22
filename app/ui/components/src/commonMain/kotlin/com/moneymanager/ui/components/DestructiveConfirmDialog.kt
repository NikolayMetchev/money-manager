package com.moneymanager.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import kotlinx.coroutines.launch
import org.lighthousegames.logging.logging

private val logger = logging()

/**
 * Shared confirmation dialog for destructive actions.
 *
 * Owns the in-flight (`isBusy`) and failure state so call sites only supply the wording and the
 * suspending action: a failure is logged and surfaced inline, leaving the dialog open so the user
 * can retry or cancel. [onConfirm] is responsible for dismissing on success (usually by calling the
 * same callback the screen passes as [onDismiss]).
 *
 * @param body extra content rendered between the consequence sentence and the error message; it
 *   receives whether the action is currently running so inputs can disable themselves.
 */
@Composable
fun DestructiveConfirmDialog(
    title: String,
    targetName: String,
    onConfirm: suspend () -> Unit,
    onDismiss: () -> Unit,
    consequence: String? = null,
    confirmLabel: String = "Delete",
    confirmEnabled: Boolean = true,
    failureMessage: String = "Failed to delete",
    body: @Composable ColumnScope.(isBusy: Boolean) -> Unit = {},
) {
    var isBusy by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberSchemaAwareCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        icon = {
            Text(
                text = "⚠️",
                style = MaterialTheme.typography.headlineMedium,
            )
        },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Are you sure you want to delete \"$targetName\"?",
                    style = MaterialTheme.typography.bodyLarge,
                )
                consequence?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                body(isBusy)
                errorMessage?.let { error -> ErrorMessageText(error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    isBusy = true
                    errorMessage = null
                    scope.launch {
                        try {
                            onConfirm()
                        } catch (expected: Exception) {
                            logger.error(expected) { "$failureMessage: ${expected.message}" }
                            errorMessage = "$failureMessage: ${expected.message}"
                            isBusy = false
                        }
                    }
                },
                enabled = !isBusy && confirmEnabled,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                if (isBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(confirmLabel)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isBusy) { Text("Cancel") }
        },
    )
}
