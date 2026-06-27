@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
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
import com.moneymanager.remotestorage.sync.RemoteDatabaseController
import com.moneymanager.remotestorage.sync.SyncProgress
import com.moneymanager.remotestorage.sync.SyncResult
import com.moneymanager.remotestorage.sync.SyncState
import com.moneymanager.remotestorage.sync.SyncStatus
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.util.onEnterKeyDown
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.jacobras.humanreadable.HumanReadable
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

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
    onReloadFromRemote: () -> Unit,
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
    LaunchedEffect(binding, sessionActive, refreshTick, currentDatabaseLocation) {
        localSize = runCatching { controller.localDatabaseSize(currentDatabaseLocation) }.getOrNull()
        remoteSize = if (binding != null) runCatching { controller.remoteArchiveSize() }.getOrNull() else null
    }

    // The controller owns the multi-device sync state; the card just reflects it.
    val syncState by controller.syncState.collectAsState()
    var confirmAction by remember { mutableStateOf<ConflictAction?>(null) }

    // Light local-only poll for unsynced changes (no network) + the session token's expiry.
    var tokenStatus by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(sessionActive, binding, refreshTick, database) {
        while (true) {
            if (sessionActive) {
                runCatching { controller.refreshLocalDirty(database) }
                tokenStatus = runCatching { controller.accessTokenExpiresAtEpochMs() }.getOrNull()?.let(::formatTokenStatus)
            } else {
                tokenStatus = null
            }
            delay(2.seconds)
        }
    }

    // Check the remote once whenever a session arms (e.g. after open/resume). Detection is otherwise
    // on demand via the "Check remote for changes" button — never polled.
    LaunchedEffect(sessionActive, binding) {
        if (sessionActive) runCatching { controller.checkRemote(database) }
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
                val isGoogleDrive = currentBinding.providerId == GOOGLE_DRIVE_PROVIDER_ID
                val locationPath =
                    if (isGoogleDrive) {
                        "$GOOGLE_DRIVE_FOLDER_NAME/${currentBinding.remoteName}"
                    } else {
                        currentBinding.remoteName
                    }
                val onOpenRemote: (() -> Unit)? =
                    if (isGoogleDrive) {
                        { uriHandler.openUri("https://drive.google.com/file/d/${currentBinding.remoteFileId}/view") }
                    } else {
                        null
                    }

                fun upload(force: Boolean) {
                    busy = true
                    scope.launch {
                        runCatching { controller.syncNow(database, force = force) { syncProgress = it } }
                            .onSuccess { result ->
                                message =
                                    when (result) {
                                        SyncResult.UPLOADED -> "Uploaded to ${currentBinding.remoteName}"
                                        SyncResult.BLOCKED ->
                                            "The remote changed since your last sync — download first, or overwrite it."
                                        SyncResult.NO_SESSION -> "No active cloud session"
                                    }
                                refreshTick++
                            }.onFailure { message = "Upload failed: ${it.message}" }
                        syncProgress = null
                        busy = false
                    }
                }

                // Re-hydrating overwrites the *currently open* working copy, so the live database must be
                // closed before restore (or SQLite reports it busy, notably on Android). The app root owns
                // the database lifecycle, so it performs the close → download → reopen as one operation.
                fun download() = onReloadFromRemote()

                BoundState(
                    providerLabel = providerLabel,
                    locationPath = locationPath,
                    onOpenRemote = onOpenRemote,
                    sessionActive = sessionActive,
                    busy = busy,
                    syncState = syncState,
                    onCheckRemote = {
                        busy = true
                        scope.launch {
                            runCatching { controller.checkRemote(database) }
                                .onSuccess { message = null }
                                .onFailure { message = "Check failed: ${it.message}" }
                            busy = false
                        }
                    },
                    onUpload = {
                        if (syncState.status == SyncStatus.CONFLICT) {
                            confirmAction = ConflictAction.UploadOverwrite
                        } else {
                            upload(force = false)
                        }
                    },
                    onDownload = {
                        if (syncState.status == SyncStatus.CONFLICT) {
                            confirmAction = ConflictAction.DownloadDiscard
                        } else {
                            download()
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

                confirmAction?.let { action ->
                    ConflictConfirmDialog(
                        action = action,
                        onConfirm = {
                            confirmAction = null
                            when (action) {
                                ConflictAction.UploadOverwrite -> upload(force = true)
                                ConflictAction.DownloadDiscard -> download()
                            }
                        },
                        onDismiss = { confirmAction = null },
                    )
                }
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

    // The setup dialog already warned about (and resolved) any name collision before calling this; a
    // non-null [overwriteFileId] means the user chose to replace that existing archive in place.
    fun startCreate(
        type: RemoteStorageType,
        config: String?,
        name: String,
        password: String,
        overwriteFileId: String?,
    ) {
        createType = null
        busy = true
        scope.launch {
            runCatching {
                controller.createRemote(type.id, config, name, currentDatabaseLocation, database, password, overwriteFileId) {
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

    fun startOpen(
        type: RemoteStorageType,
        config: String?,
        file: RemoteFile,
        password: String,
    ) {
        openType = null
        busy = true
        scope.launch {
            runCatching {
                controller.openRemote(type.id, config, file, remoteCacheLocation(file.name), password) {
                    syncProgress = it
                }
            }.onSuccess { location ->
                // Reflect the now-bound remote session immediately; the card otherwise stays "unbound"
                // until a full recomposition with fresh initial state.
                binding = controller.activeBinding()
                sessionActive = controller.hasActiveSession()
                refreshTick++
                onRequestSwitchDatabase(location)
            }.onFailure { message = "Open failed: ${it.message}" }
            syncProgress = null
            busy = false
        }
    }

    createType?.let { type ->
        GoogleDriveSetupDialog(
            mode = GoogleDriveSetupMode.CREATE,
            defaultName = defaultArchiveName,
            onSignIn = { config -> controller.signInTo(type.id, config) },
            onList = { config -> controller.list(type.id, config) },
            onCreate = { config, name, password, overwriteFileId ->
                startCreate(type, config, name, password, overwriteFileId)
            },
            // The create dialog offers "Open" when the typed name clashes with an existing archive.
            onOpen = { config, file, password -> startOpen(type, config, file, password) },
            onDismiss = { createType = null },
        )
    }

    openType?.let { type ->
        GoogleDriveSetupDialog(
            mode = GoogleDriveSetupMode.OPEN,
            defaultName = defaultArchiveName,
            onSignIn = { config -> controller.signInTo(type.id, config) },
            onList = { config -> controller.list(type.id, config) },
            onCreate = { _, _, _, _ -> },
            onOpen = { config, file, password -> startOpen(type, config, file, password) },
            onDismiss = { openType = null },
        )
    }

    if (showResume) {
        PasswordDialog(
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

/** Which side of a [SyncStatus.CONFLICT] the user chose to keep (the other side's changes are lost). */
private enum class ConflictAction { UploadOverwrite, DownloadDiscard }

@Composable
private fun BoundState(
    providerLabel: String,
    locationPath: String,
    onOpenRemote: (() -> Unit)?,
    sessionActive: Boolean,
    busy: Boolean,
    syncState: SyncState,
    onCheckRemote: () -> Unit,
    onUpload: () -> Unit,
    onDownload: () -> Unit,
    onResume: () -> Unit,
    onDisconnect: () -> Unit,
) {
    // syncState.busy is set the instant a reload starts (see RemoteDatabaseController.beginBusy), so the
    // actions disable immediately on click — before the loading screen replaces this card.
    val actionsDisabled = busy || syncState.busy
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
    if (sessionActive) {
        SyncStatusLine(syncState.status)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCheckRemote, enabled = !actionsDisabled, modifier = Modifier.weight(1f)) {
                Text("Check remote for changes")
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onUpload,
                enabled = !actionsDisabled && syncState.canUpload,
                modifier = Modifier.weight(1f),
            ) {
                Text("Upload")
            }
            OutlinedButton(
                onClick = onDownload,
                enabled = !actionsDisabled && syncState.canDownload,
                modifier = Modifier.weight(1f),
            ) {
                Text("Download")
            }
        }
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!sessionActive) {
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
private fun SyncStatusLine(status: SyncStatus) {
    val (text, highlight) =
        when (status) {
            SyncStatus.IN_SYNC -> "✓ Everything is synced" to true
            SyncStatus.LOCAL_AHEAD -> "Local changes not uploaded" to false
            SyncStatus.REMOTE_AHEAD -> "Another device updated this database — download to continue" to false
            SyncStatus.CONFLICT -> "Conflict: both this device and another device changed the database" to false
            SyncStatus.NO_SESSION -> "" to false
        }
    if (text.isEmpty()) return
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun ConflictConfirmDialog(
    action: ConflictAction,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val (title, body, confirmLabel) =
        when (action) {
            ConflictAction.UploadOverwrite ->
                Triple(
                    "Overwrite remote changes?",
                    "Another device changed this database since your last sync. Uploading now will overwrite " +
                        "those changes with your local copy. This can't be undone.",
                    "Overwrite remote",
                )
            ConflictAction.DownloadDiscard ->
                Triple(
                    "Discard local changes?",
                    "Another device changed this database since your last sync. Downloading now will discard " +
                        "your local unsynced changes. This can't be undone.",
                    "Discard local",
                )
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PasswordDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    val submit = { if (password.isNotEmpty()) onConfirm(password) }
    val passwordFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { passwordFocusRequester.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter sync password") },
        text = {
            PasswordField(
                password,
                { password = it },
                "Password",
                modifier = Modifier.focusRequester(passwordFocusRequester),
                onSubmit = submit,
            )
        },
        confirmButton = {
            TextButton(onClick = submit, enabled = password.isNotEmpty()) { Text("Resume sync") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Shared password input. When [onSubmit] is provided the field confirms the dialog on Enter (desktop /
 * hardware keyboard) and on the soft-keyboard "Done" action (Android); the caller gates [onSubmit] on
 * the same validity its confirm button uses.
 */
@Composable
internal fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    onSubmit: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = if (onSubmit != null) modifier.onEnterKeyDown(onSubmit) else modifier,
        keyboardOptions = KeyboardOptions(imeAction = if (onSubmit != null) ImeAction.Done else ImeAction.Default),
        keyboardActions = KeyboardActions(onDone = { onSubmit?.invoke() }),
    )
}
