package com.moneymanager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.moneymanager.compose.filepicker.rememberFilePicker
import com.moneymanager.remotestorage.RemoteFile
import com.moneymanager.remotestorage.googledrive.GoogleDriveCredentials
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import kotlinx.coroutines.launch

private const val CLOUD_CONSOLE_CREDENTIALS_URL = "https://console.cloud.google.com/apis/credentials"

private val SETUP_INSTRUCTIONS =
    listOf(
        "Open the Google Cloud Console (button below) and create or pick a project.",
        "In \"APIs & Services → Library\", enable the \"Google Drive API\".",
        "In \"APIs & Services → OAuth consent screen\", configure it and add your Google account under " +
            "\"Test users\".",
        "In \"Credentials → Create credentials → OAuth client ID\", choose application type \"Desktop app\" " +
            "and download the JSON.",
        "Paste the contents of that downloaded credentials.json below, then sign in with Google.",
    )

/**
 * Setup wizard for Google Drive, then collect the archive name + password (create) or pick a file +
 * password (open).
 *
 * Two sign-in shapes, selected by [requiresConfig]:
 * - **true** (JVM/desktop): guided bring-your-own-credentials — step-by-step instructions, let the user
 *   paste their own `credentials.json`, sign in via the loopback consent flow. The wizard never sees
 *   app-owned secrets — only the user's OAuth client, serialized into the binding's provider config via
 *   [GoogleDriveCredentials.toConfig].
 * - **false** (Android): native `AuthorizationClient` — no credentials step; a single "Sign in with
 *   Google" button triggers the account/consent sheet, and `config` is null.
 */
@Composable
fun GoogleDriveSetupDialog(
    mode: GoogleDriveSetupMode,
    defaultName: String,
    requiresConfig: Boolean,
    onSignIn: suspend (config: String?) -> Unit,
    onList: suspend (config: String?) -> List<RemoteFile>,
    onCreate: (config: String?, name: String, password: String) -> Unit,
    onOpen: (config: String?, file: RemoteFile, password: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val scope = rememberSchemaAwareCoroutineScope()

    var credentialsJson by remember { mutableStateOf("") }
    var connecting by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var files by remember { mutableStateOf<List<RemoteFile>>(emptyList()) }
    var selected by remember { mutableStateOf<RemoteFile?>(null) }
    var name by remember { mutableStateOf(defaultName) }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }

    val parsedConfig = remember(credentialsJson) { GoogleDriveCredentials.parseCredentialsJson(credentialsJson)?.toConfig() }
    // The config sent to the callbacks: the user's parsed credentials on JVM, null when auth is native.
    val effectiveConfig = if (requiresConfig) parsedConfig else null
    val canSignIn = !requiresConfig || parsedConfig != null

    val filePicker =
        rememberFilePicker(mimeTypes = listOf("application/json")) { result ->
            result?.let { credentialsJson = it.content }
        }

    fun connect() {
        if (!canSignIn) return
        connecting = true
        error = null
        scope.launch {
            runCatching {
                onSignIn(effectiveConfig)
                if (mode == GoogleDriveSetupMode.OPEN) files = onList(effectiveConfig)
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
                if (!connected && !requiresConfig) {
                    Text(
                        "Sign in with your Google account to store this database in Google Drive.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else if (!connected) {
                    SETUP_INSTRUCTIONS.forEachIndexed { index, line ->
                        Text("${index + 1}. $line", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(
                        onClick = { uriHandler.openUri(CLOUD_CONSOLE_CREDENTIALS_URL) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Open Google Cloud Console") }
                    OutlinedButton(
                        onClick = { filePicker.launch() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Choose credentials.json file…") }
                    when {
                        parsedConfig != null ->
                            Text(
                                "✓ Loaded OAuth client credentials.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        credentialsJson.isNotBlank() ->
                            Text(
                                "That file isn't a Google OAuth client (expected a Desktop app credentials.json).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                    }
                    Text("…or paste the credentials.json contents:", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = credentialsJson,
                        onValueChange = { credentialsJson = it },
                        label = { Text("credentials.json") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
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
                    TextButton(onClick = ::connect, enabled = canSignIn && !connecting) {
                        Text("Sign in with Google")
                    }
                mode == GoogleDriveSetupMode.CREATE ->
                    TextButton(
                        onClick = { onCreate(effectiveConfig, name, password) },
                        enabled = createValid,
                    ) { Text("Upload") }
                else ->
                    TextButton(
                        onClick = { selected?.let { file -> onOpen(effectiveConfig, file, password) } },
                        enabled = openValid,
                    ) { Text("Open") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
