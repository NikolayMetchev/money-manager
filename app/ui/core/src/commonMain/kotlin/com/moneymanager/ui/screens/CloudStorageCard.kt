@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.domain.model.defaultRemoteArchiveName
import com.moneymanager.domain.model.remoteCacheLocation
import com.moneymanager.remotestorage.RemoteFile
import com.moneymanager.remotestorage.RemoteStorageType
import com.moneymanager.remotestorage.googledrive.GOOGLE_DRIVE_FOLDER_NAME
import com.moneymanager.remotestorage.googledrive.GOOGLE_DRIVE_PROVIDER_ID
import com.moneymanager.remotestorage.sync.RemoteDatabaseBinding
import com.moneymanager.remotestorage.sync.RemoteDatabaseController
import com.moneymanager.remotestorage.sync.SyncProgress
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock
import nl.jacobras.humanreadable.HumanReadable

/**
 * Settings card for backing the active database with a remote-storage provider (issue #86): create a
 * remote copy of the current database, open one from a provider, or sync the active one.
 */
@Composable
fun CloudStorageCard(
    controller: RemoteDatabaseController,
    database: MoneyManagerDatabaseWrapper,
    currentDatabaseLocation: DbLocation,
    onRequestSwitchDatabase: (DbLocation) -> Unit,
) {
    val scope = rememberSchemaAwareCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var binding by remember { mutableStateOf(controller.activeBinding()) }
    var sessionActive by remember { mutableStateOf(controller.hasActiveSession()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    var createType by remember { mutableStateOf<RemoteStorageType?>(null) }
    var openType by remember { mutableStateOf<RemoteStorageType?>(null) }
    var showResume by remember { mutableStateOf(false) }
    var syncProgress by remember { mutableStateOf<SyncProgress?>(null) }

    var refreshTick by remember { mutableStateOf(0) }
    var localSize by remember { mutableStateOf<Long?>(null) }
    var remoteSize by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(binding, sessionActive, refreshTick) {
        localSize = runCatching { controller.localDatabaseSize(currentDatabaseLocation) }.getOrNull()
        remoteSize = if (binding != null) runCatching { controller.remoteArchiveSize() }.getOrNull() else null
    }

    // Poll for unsynced changes (and the session token's expiry) so the card reflects live state.
    var dirty by remember { mutableStateOf(false) }
    var tokenStatus by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(sessionActive, binding, refreshTick) {
        while (true) {
            dirty = sessionActive && runCatching { controller.hasUnsyncedChanges(database) }.getOrDefault(false)
            tokenStatus =
                if (sessionActive) {
                    runCatching { controller.accessTokenExpiresAtEpochMs() }.getOrNull()?.let(::formatTokenStatus)
                } else {
                    null
                }
            delay(2000)
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "Cloud storage", style = MaterialTheme.typography.titleMedium)

            val currentBinding = binding
            if (currentBinding == null) {
                Text(
                    text = "This database is stored locally. Back it up to a provider to keep it encrypted off-device.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                controller.providerFactory.types().forEach { type ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(onClick = { createType = type }, modifier = Modifier.weight(1f)) {
                            Text("Store in ${type.displayName}…")
                        }
                        OutlinedButton(onClick = { openType = type }, modifier = Modifier.weight(1f)) {
                            Text("Open from ${type.displayName}…")
                        }
                    }
                }
            } else {
                val type = controller.providerFactory.types().firstOrNull { it.id == currentBinding.providerId }
                val providerLabel = type?.displayName ?: currentBinding.providerId
                val locationPath =
                    when {
                        type?.requiresFolder == true ->
                            "${currentBinding.providerConfig.orEmpty().trimEnd('/', '\\')}/${currentBinding.remoteName}"
                        currentBinding.providerId == GOOGLE_DRIVE_PROVIDER_ID ->
                            "$GOOGLE_DRIVE_FOLDER_NAME/${currentBinding.remoteName}"
                        else -> currentBinding.remoteName
                    }
                val onOpenRemote: (() -> Unit)? =
                    if (currentBinding.providerId == GOOGLE_DRIVE_PROVIDER_ID) {
                        { uriHandler.openUri("https://drive.google.com/file/d/${currentBinding.remoteFileId}/view") }
                    } else {
                        null
                    }
                BoundState(
                    providerLabel = providerLabel,
                    locationPath = locationPath,
                    onOpenRemote = onOpenRemote,
                    sessionActive = sessionActive,
                    busy = busy,
                    dirty = dirty,
                    onSyncNow = {
                        busy = true
                        scope.launch {
                            runCatching { controller.syncNow(database) { syncProgress = it } }
                                .onSuccess {
                                    message = "Synced to ${currentBinding.remoteName}"
                                    refreshTick++
                                }.onFailure { message = "Sync failed: ${it.message}" }
                            syncProgress = null
                            busy = false
                        }
                    },
                    onResume = { showResume = true },
                    onDisconnect = {
                        controller.unbind()
                        binding = null
                        sessionActive = false
                        message = "Disconnected from cloud storage (local copy kept)"
                    },
                )
            }

            StorageSizes(localSize = localSize, remoteSize = remoteSize)

            tokenStatus?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }

            syncProgress?.let { progress ->
                Text(text = progress.message, style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            message?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }
        }
    }

    val defaultArchiveName =
        defaultRemoteArchiveName(
            currentDatabaseLocation.toString().substringAfterLast('/').substringAfterLast('\\'),
        )

    // Shared create/open handlers so the folder dialogs and the Google Drive wizard drive the same flow.
    fun startCreate(type: RemoteStorageType, config: String?, name: String, password: String) {
        createType = null
        busy = true
        scope.launch {
            runCatching {
                controller.createRemote(type.id, config, name, currentDatabaseLocation, database, password) {
                    syncProgress = it
                }
            }.onSuccess {
                binding = it
                sessionActive = true
                message = "Stored in ${type.displayName} as ${it.remoteName}"
            }.onFailure { message = "Upload failed: ${it.message}" }
            syncProgress = null
            busy = false
        }
    }

    fun startOpen(type: RemoteStorageType, config: String?, file: RemoteFile, password: String) {
        openType = null
        busy = true
        scope.launch {
            runCatching {
                controller.openRemote(type.id, config, file, remoteCacheLocation(file.name), password) {
                    syncProgress = it
                }
            }.onSuccess { onRequestSwitchDatabase(it) }
                .onFailure { message = "Open failed: ${it.message}" }
            syncProgress = null
            busy = false
        }
    }

    createType?.let { type ->
        if (type.id == GOOGLE_DRIVE_PROVIDER_ID) {
            GoogleDriveSetupDialog(
                mode = GoogleDriveSetupMode.CREATE,
                defaultName = defaultArchiveName,
                onSignIn = { config -> controller.signInTo(type.id, config) },
                onList = { config -> controller.list(type.id, config) },
                onCreate = { config, name, password -> startCreate(type, config, name, password) },
                onOpen = { _, _, _ -> },
                onDismiss = { createType = null },
            )
        } else {
            CreateRemoteDialog(
                type = type,
                defaultName = defaultArchiveName,
                onDismiss = { createType = null },
                onConfirm = { config, name, password -> startCreate(type, config, name, password) },
            )
        }
    }

    openType?.let { type ->
        if (type.id == GOOGLE_DRIVE_PROVIDER_ID) {
            GoogleDriveSetupDialog(
                mode = GoogleDriveSetupMode.OPEN,
                defaultName = defaultArchiveName,
                onSignIn = { config -> controller.signInTo(type.id, config) },
                onList = { config -> controller.list(type.id, config) },
                onCreate = { _, _, _ -> },
                onOpen = { config, file, password -> startOpen(type, config, file, password) },
                onDismiss = { openType = null },
            )
        } else {
            OpenRemoteDialog(
                type = type,
                onDismiss = { openType = null },
                onList = { config -> controller.list(type.id, config) },
                onConfirm = { config, file, password -> startOpen(type, config, file, password) },
            )
        }
    }

    if (showResume) {
        PasswordDialog(
            title = "Enter sync password",
            confirmLabel = "Resume sync",
            onDismiss = { showResume = false },
            onConfirm = { password ->
                showResume = false
                busy = true
                scope.launch {
                    val ok = runCatching { controller.resume(password) }.getOrDefault(false)
                    sessionActive = ok
                    message = if (ok) "Sync resumed" else "Wrong password"
                    busy = false
                }
            },
        )
    }
}

/**
 * A human-friendly note about when the session's access token refreshes. Google access tokens are
 * opaque (not JWTs), so the expiry isn't decoded from the token — it comes from the `expires_in` the
 * token endpoint returned, persisted alongside the token. The refresh is automatic and silent.
 */
private fun formatTokenStatus(expiresAtEpochMs: Long): String {
    val minutes = (expiresAtEpochMs - Clock.System.now().toEpochMilliseconds()) / 60_000L
    return when {
        minutes > 1 -> "Access token refreshes in ~$minutes min (automatic)"
        minutes in 0L..1L -> "Access token refreshes shortly (automatic)"
        else -> "Access token expired — refreshes automatically on next use"
    }
}

@Composable
private fun StorageSizes(
    localSize: Long?,
    remoteSize: Long?,
) {
    if (localSize == null && remoteSize == null) return
    localSize?.let { Text("Local database: ${HumanReadable.fileSize(it)}", style = MaterialTheme.typography.bodySmall) }
    remoteSize?.let { remote ->
        val ratio = localSize?.takeIf { it > 0 }?.let { " (${remote * 100 / it}% of local)" } ?: ""
        Text("Remote, compressed: ${HumanReadable.fileSize(remote)}$ratio", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun BoundState(
    providerLabel: String,
    locationPath: String,
    onOpenRemote: (() -> Unit)?,
    sessionActive: Boolean,
    busy: Boolean,
    dirty: Boolean,
    onSyncNow: () -> Unit,
    onResume: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Text(text = "Stored on $providerLabel", style = MaterialTheme.typography.bodyMedium)
    if (onOpenRemote != null) {
        Text(
            text = "$locationPath ↗",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(onClick = onOpenRemote),
        )
    } else {
        Text(text = locationPath, style = MaterialTheme.typography.bodySmall)
    }
    if (sessionActive && !dirty) {
        Text(
            text = "✓ Everything is synced",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    } else if (sessionActive) {
        Text(
            text = "Unsynced changes",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (sessionActive) {
            // Enabled only when there are local changes to upload.
            OutlinedButton(onClick = onSyncNow, enabled = !busy && dirty, modifier = Modifier.weight(1f)) {
                Text("Sync now")
            }
        } else {
            OutlinedButton(onClick = onResume, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text("Enter password to sync")
            }
        }
        OutlinedButton(onClick = onDisconnect, enabled = !busy, modifier = Modifier.weight(1f)) {
            Text("Disconnect")
        }
    }
}

@Composable
private fun CreateRemoteDialog(
    type: RemoteStorageType,
    defaultName: String,
    onDismiss: () -> Unit,
    onConfirm: (config: String?, name: String, password: String) -> Unit,
) {
    var folder by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(defaultName.ifBlank { "money_manager.mmenc" }) }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    val valid =
        name.isNotBlank() &&
            password.isNotEmpty() &&
            password == confirmPassword &&
            (!type.requiresFolder || folder.isNotBlank())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Store in ${type.displayName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (type.requiresFolder) {
                    OutlinedTextField(folder, { folder = it }, label = { Text("Folder path") }, singleLine = true)
                }
                OutlinedTextField(name, { name = it }, label = { Text("Archive name") }, singleLine = true)
                PasswordField(password, { password = it }, "Password")
                PasswordField(confirmPassword, { confirmPassword = it }, "Confirm password")
                Text(
                    "The database is compressed and encrypted with this password. Keep it safe — it can't be recovered.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(folder.ifBlank { null }, name, password) }, enabled = valid) {
                Text("Upload")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun OpenRemoteDialog(
    type: RemoteStorageType,
    onDismiss: () -> Unit,
    onList: suspend (config: String?) -> List<RemoteFile>,
    onConfirm: (config: String?, file: RemoteFile, password: String) -> Unit,
) {
    val scope = rememberSchemaAwareCoroutineScope()
    var folder by remember { mutableStateOf("") }
    var files by remember { mutableStateOf<List<RemoteFile>>(emptyList()) }
    var selected by remember { mutableStateOf<RemoteFile?>(null) }
    var password by remember { mutableStateOf("") }
    var listError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open from ${type.displayName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (type.requiresFolder) {
                    OutlinedTextField(folder, { folder = it }, label = { Text("Folder path") }, singleLine = true)
                }
                OutlinedButton(onClick = {
                    scope.launch {
                        runCatching { onList(folder.ifBlank { null }) }
                            .onSuccess {
                                files = it
                                listError = null
                            }.onFailure { listError = it.message }
                    }
                }) { Text("List databases") }
                listError?.let { Text("Error: $it", style = MaterialTheme.typography.bodySmall) }
                files.forEach { file ->
                    TextButton(onClick = { selected = file }) {
                        Text((if (selected == file) "✓ " else "") + file.name)
                    }
                }
                if (selected != null) {
                    PasswordField(password, { password = it }, "Password")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selected?.let { onConfirm(folder.ifBlank { null }, it, password) } },
                enabled = selected != null && password.isNotEmpty(),
            ) { Text("Open") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PasswordDialog(
    title: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { PasswordField(password, { password = it }, "Password") },
        confirmButton = {
            TextButton(onClick = { onConfirm(password) }, enabled = password.isNotEmpty()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
    )
}
