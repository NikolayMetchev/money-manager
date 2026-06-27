package com.moneymanager.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Shown when closing a cloud-backed database. Lets the user decide, per close:
 *  - whether to **upload** the local changes to the remote (pre-ticked only when the working copy
 *    actually changed since its last upload), and
 *  - whether to **keep** the local working copy on this device (default on, so the next launch is
 *    instant and offline) or compress+upload and delete it (the original behavior).
 *
 * When an upload is requested but no sync session is armed (the database was opened straight from a
 * kept local copy, so no password was entered this run), a password field appears — the password is
 * only needed to encrypt the upload, so it is collected here, on demand.
 */
@Composable
fun CloseDatabaseDialog(
    remoteName: String,
    localChanged: Boolean,
    needsPassword: Boolean,
    onConfirm: (upload: Boolean, keepLocal: Boolean, password: String?) -> Unit,
    onCancel: () -> Unit,
) {
    var upload by remember { mutableStateOf(localChanged) }
    var keepLocal by remember { mutableStateOf(true) }
    var password by remember { mutableStateOf("") }

    val passwordRequired = upload && needsPassword
    val confirmEnabled = !passwordRequired || password.isNotEmpty()

    MaterialTheme {
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text("Close database") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CheckboxRow(
                        checked = upload,
                        onCheckedChange = { upload = it },
                        // Nothing changed since the last upload → there's nothing to push, so lock it off.
                        enabled = localChanged,
                        label = "Upload changes to “$remoteName”",
                    )
                    CheckboxRow(
                        checked = keepLocal,
                        onCheckedChange = { keepLocal = it },
                        label = "Keep local copy on this device",
                    )
                    if (passwordRequired) {
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { onConfirm(upload, keepLocal, if (passwordRequired) password else null) },
                    enabled = confirmEnabled,
                ) { Text("Close") }
            },
            dismissButton = {
                TextButton(onClick = onCancel) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun CheckboxRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
