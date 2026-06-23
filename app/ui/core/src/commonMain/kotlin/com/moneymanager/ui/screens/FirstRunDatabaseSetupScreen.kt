package com.moneymanager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.moneymanager.database.DatabaseInitializationProgress
import com.moneymanager.database.DatabaseManager
import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.DEFAULT_DATABASE_NAME
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.domain.model.defaultRemoteArchiveName
import com.moneymanager.domain.model.remoteCacheLocation
import com.moneymanager.remotestorage.RemoteFile
import com.moneymanager.remotestorage.RemoteStorageType
import com.moneymanager.remotestorage.sync.RemoteDatabaseController
import com.moneymanager.remotestorage.sync.SyncProgress
import com.moneymanager.ui.AppServices
import com.moneymanager.ui.DatabasePickerMode
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.rememberDatabaseLocationPicker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * First-run chooser shown when there is no remembered database and no remote binding: the user must
 * pick one of four options (no silent default). Local options reuse the platform database picker;
 * remote options reuse the Google Drive setup dialog. Creating a new remote database opens a freshly
 * seeded local working copy first, then uploads it (so [RemoteDatabaseController.createRemote] has an
 * open database to push).
 *
 * Remote progress is shown **inline** (this screen stays composed) rather than by switching to the
 * shared loading screen: the create/open coroutine runs on this screen's scope, so navigating away
 * before it finishes would cancel it.
 */
@Composable
fun FirstRunDatabaseSetupScreen(
    databaseManager: DatabaseManager,
    remoteController: RemoteDatabaseController?,
    createAppServices: (MoneyManagerDatabaseWrapper) -> AppServices,
    onLocalReady: (DbLocation) -> Unit,
    onRemoteOpened: (DbLocation) -> Unit,
    onRemoteCreated: (DbLocation, AppServices, MoneyManagerDatabaseWrapper) -> Unit,
    onErrorLog: (String, Throwable) -> Unit,
) {
    val scope = rememberSchemaAwareCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<DatabaseInitializationProgress?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    var createRemoteType by remember { mutableStateOf<RemoteStorageType?>(null) }
    var openRemoteType by remember { mutableStateOf<RemoteStorageType?>(null) }

    // The picker delivers its result asynchronously on both platforms (JVM posts after the blocking AWT
    // dialog; Android renders the dialog on the next recomposition). A null result = cancelled, so we stay
    // on this screen — there is no skip.
    val picker =
        rememberDatabaseLocationPicker { location ->
            if (location != null) onLocalReady(location)
        }

    // The setup dialog already warned about (and resolved) any name collision before calling this; a
    // non-null [overwriteFileId] means the user chose to replace that existing archive in place.
    fun startCreateRemote(
        type: RemoteStorageType,
        config: String?,
        name: String,
        password: String,
        overwriteFileId: String?,
    ) {
        createRemoteType = null
        busy = true
        message = null
        val cache = remoteCacheLocation(name)
        scope.launch {
            var database: MoneyManagerDatabaseWrapper? = null
            try {
                progress = DatabaseInitializationProgress("Creating database...", 0, 1)
                // A new remote database starts as a freshly seeded local working copy that we then upload.
                val opened = databaseManager.openDatabaseWithProgress(cache) { progress = it }
                database = opened
                progress = DatabaseInitializationProgress("Preparing application services...", 1, 1)
                val services = createAppServices(opened)
                services.deviceId
                remoteController!!.createRemote(type.id, config, name, cache, opened, password, overwriteFileId) {
                    progress = it.toInitializationProgress()
                }
                // createRemote persisted the binding, armed the session and captured the sync baseline,
                // so the database is ready to use as-is — hand it straight to the host.
                onRemoteCreated(cache, services, opened)
            } catch (expected: CancellationException) {
                throw expected
            } catch (expected: Exception) {
                onErrorLog("Failed to create remote database", expected)
                // No binding is persisted until the upload succeeds, so clean up the orphan working copy.
                runCatching {
                    database?.close()
                    databaseManager.deleteDatabase(cache)
                }
                message = "Couldn't create remote database: ${expected.message ?: expected::class.simpleName}"
                progress = null
                busy = false
            }
        }
    }

    fun startOpenRemote(
        type: RemoteStorageType,
        config: String?,
        file: RemoteFile,
        password: String,
    ) {
        openRemoteType = null
        busy = true
        message = null
        scope.launch {
            try {
                progress = DatabaseInitializationProgress("Opening from cloud...", 0, 1)
                val location =
                    remoteController!!.openRemote(type.id, config, file, remoteCacheLocation(file.name), password) {
                        progress = it.toInitializationProgress()
                    }
                onRemoteOpened(location)
            } catch (expected: CancellationException) {
                throw expected
            } catch (expected: Exception) {
                onErrorLog("Failed to open remote database", expected)
                message = "Couldn't open remote database: ${expected.message ?: expected::class.simpleName}"
                progress = null
                busy = false
            }
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Welcome to Money Manager",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Choose a database to get started.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(24.dp))

                val buttonModifier = Modifier.fillMaxWidth().widthIn(max = 360.dp)
                OutlinedButton(
                    onClick = { picker.launch(DatabasePickerMode.CREATE) },
                    enabled = !busy,
                    modifier = buttonModifier,
                ) { Text("New local database") }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { picker.launch(DatabasePickerMode.OPEN) },
                    enabled = !busy,
                    modifier = buttonModifier,
                ) { Text("Open existing local database") }

                remoteController?.providerFactory?.types()?.forEach { type ->
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { createRemoteType = type },
                        enabled = !busy,
                        modifier = buttonModifier,
                    ) { Text("New database on ${type.displayName}") }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { openRemoteType = type },
                        enabled = !busy,
                        modifier = buttonModifier,
                    ) { Text("Open database from ${type.displayName}") }
                }

                progress?.let {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(it.text, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { it.fraction },
                        modifier = buttonModifier,
                    )
                }

                message?.let {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }

    val defaultArchiveName = defaultRemoteArchiveName(DEFAULT_DATABASE_NAME)

    createRemoteType?.let { type ->
        GoogleDriveSetupDialog(
            mode = GoogleDriveSetupMode.CREATE,
            defaultName = defaultArchiveName,
            onSignIn = { config -> remoteController!!.signInTo(type.id, config) },
            onList = { config -> remoteController!!.list(type.id, config) },
            onCreate = { config, name, password, overwriteFileId ->
                startCreateRemote(type, config, name, password, overwriteFileId)
            },
            // The create dialog offers "Open" when the typed name clashes with an existing archive.
            onOpen = { config, file, password -> startOpenRemote(type, config, file, password) },
            onDismiss = { createRemoteType = null },
        )
    }

    openRemoteType?.let { type ->
        GoogleDriveSetupDialog(
            mode = GoogleDriveSetupMode.OPEN,
            defaultName = defaultArchiveName,
            onSignIn = { config -> remoteController!!.signInTo(type.id, config) },
            onList = { config -> remoteController!!.list(type.id, config) },
            onCreate = { _, _, _, _ -> },
            onOpen = { config, file, password -> startOpenRemote(type, config, file, password) },
            onDismiss = { openRemoteType = null },
        )
    }
}

private fun SyncProgress.toInitializationProgress() = DatabaseInitializationProgress(message, (fraction * 100).toInt(), 100)
