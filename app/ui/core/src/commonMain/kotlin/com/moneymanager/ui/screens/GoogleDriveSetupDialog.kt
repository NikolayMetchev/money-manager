package com.moneymanager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.moneymanager.remotestorage.RemoteFile
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import kotlinx.coroutines.launch

/**
 * Google Drive sign-in, then collect the archive name + password (create) or pick a file + password
 * (open). A single "Sign in with Google" button triggers the platform consent flow — the desktop
 * loopback flow with the app's shipped OAuth client on JVM, the native `AuthorizationClient` on
 * Android. The provider config is supplied by the build/platform, so the callbacks pass a null
 * `config` (per-binding overrides, if any, are applied below the UI).
 */
@Composable
fun GoogleDriveSetupDialog(
    mode: GoogleDriveSetupMode,
    defaultName: String,
    onSignIn: suspend (config: String?) -> Unit,
    onList: suspend (config: String?) -> List<RemoteFile>,
    onCreate: (config: String?, name: String, password: String) -> Unit,
    onOpen: (config: String?, file: RemoteFile, password: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberSchemaAwareCoroutineScope()

    var connecting by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var files by remember { mutableStateOf<List<RemoteFile>>(emptyList()) }
    var selected by remember { mutableStateOf<RemoteFile?>(null) }
    var name by remember { mutableStateOf(defaultName) }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }

    fun connect() {
        connecting = true
        error = null
        scope.launch {
            runCatching {
                onSignIn(null)
                if (mode == GoogleDriveSetupMode.OPEN) files = onList(null)
            }.onSuccess { connected = true }
                .onFailure { error = it.message ?: "Google sign-in failed" }
            connecting = false
        }
    }

    val createValid =
        connected && name.isNotBlank() && password.isNotEmpty() && password == confirmPassword
    val openValid = connected && selected != null && password.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (mode == GoogleDriveSetupMode.CREATE) "Store in Google Drive" else "Open from Google Drive") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!connected) {
                    Text(
                        "Sign in with your Google account to store this database in Google Drive.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else if (mode == GoogleDriveSetupMode.CREATE) {
                    Text("Connected to Google Drive.", color = MaterialTheme.colorScheme.primary)
                    OutlinedTextField(name, { name = it }, label = { Text("Archive name") }, singleLine = true)
                    PasswordField(password, { password = it }, "Password")
                    PasswordField(confirmPassword, { confirmPassword = it }, "Confirm password")
                    Text(
                        "The database is compressed and encrypted with this password. Keep it safe — it can't " +
                            "be recovered.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text("Connected to Google Drive.", color = MaterialTheme.colorScheme.primary)
                    if (files.isEmpty()) {
                        Text("No Money Manager databases found on this account.", style = MaterialTheme.typography.bodySmall)
                    }
                    files.forEach { file ->
                        TextButton(onClick = { selected = file }) {
                            Text((if (selected == file) "✓ " else "") + file.name)
                        }
                    }
                    if (selected != null) PasswordField(password, { password = it }, "Password")
                }

                if (connecting) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                error?.let { Text("Error: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            when {
                !connected ->
                    TextButton(onClick = ::connect, enabled = !connecting) {
                        Text("Sign in with Google")
                    }
                mode == GoogleDriveSetupMode.CREATE ->
                    TextButton(
                        onClick = { onCreate(null, name, password) },
                        enabled = createValid,
                    ) { Text("Upload") }
                else ->
                    TextButton(
                        onClick = { selected?.let { file -> onOpen(null, file, password) } },
                        enabled = openValid,
                    ) { Text("Open") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
