package com.moneymanager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.moneymanager.database.DatabaseInitializationProgress
import com.moneymanager.database.DatabaseManager
import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.domain.model.dbLocationFromString
import com.moneymanager.localsettings.KEY_LAST_DATABASE
import com.moneymanager.localsettings.LocalSettings
import com.moneymanager.remotestorage.sync.RemoteDatabaseController
import com.moneymanager.ui.components.DatabaseSchemaErrorDialog
import com.moneymanager.ui.components.DatabaseStartupProgressScreen
import com.moneymanager.ui.error.GlobalSchemaErrorState
import com.moneymanager.ui.error.SchemaErrorDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private sealed class AppDatabaseState {
    data class Loading(
        val progress: DatabaseInitializationProgress = initialDatabaseProgress(),
    ) : AppDatabaseState()

    data class Loaded(
        val location: DbLocation,
        val services: AppServices,
        val database: MoneyManagerDatabaseWrapper,
    ) : AppDatabaseState()

    data class Error(
        val location: DbLocation,
        val error: Throwable,
    ) : AppDatabaseState()
}

private data class RemoteUnlockState(
    val prompt: String,
    val error: String? = null,
)

private fun initialDatabaseProgress() =
    DatabaseInitializationProgress(
        text = "Finding the default database...",
        completedSteps = 0,
        totalSteps = 1,
    )

@Composable
fun AppStartupHost(
    databaseManager: DatabaseManager,
    appVersion: AppVersion,
    localSettings: LocalSettings,
    createAppServices: (MoneyManagerDatabaseWrapper) -> AppServices,
    onInfoLog: (String) -> Unit,
    onErrorLog: (String, Throwable) -> Unit,
    remoteController: RemoteDatabaseController? = null,
    onDatabaseReady: (MoneyManagerDatabaseWrapper?, DbLocation?) -> Unit = { _, _ -> },
) {
    val scope = rememberCoroutineScope()
    var databaseState by remember { mutableStateOf<AppDatabaseState>(AppDatabaseState.Loading()) }
    var switchError by remember { mutableStateOf<Pair<DbLocation, Throwable>?>(null) }
    var remoteUnlock by remember { mutableStateOf<RemoteUnlockState?>(null) }

    LaunchedEffect(Unit) {
        // When the active database is cloud-backed its local copy was deleted on the previous close,
        // so it must be restored from the remote — which requires the user's password first.
        val binding = remoteController?.activeBinding()
        if (binding != null) {
            remoteUnlock = RemoteUnlockState(prompt = "Unlock “${binding.remoteName}” from cloud storage")
            return@LaunchedEffect
        }
        val location = resolveStartupLocation(databaseManager, localSettings)
        val error =
            openAndLoad(
                location = location,
                databaseManager = databaseManager,
                createAppServices = createAppServices,
                databaseStateUpdater = { databaseState = it },
                onInfoLog = onInfoLog,
                onErrorLog = onErrorLog,
            )
        if (error == null) {
            localSettings.putString(KEY_LAST_DATABASE, location.toString())
        }
    }

    val globalSchemaError by GlobalSchemaErrorState.schemaError.collectAsState()
    val effectiveSchemaError: Pair<DbLocation, Throwable>? =
        globalSchemaError
            ?.takeIf { info -> SchemaErrorDetector.isSchemaError(info.error) }
            ?.let { info ->
                val location =
                    (databaseState as? AppDatabaseState.Loaded)?.location
                        ?: databaseManager.getDefaultLocation()
                location to info.error
            } ?: (databaseState as? AppDatabaseState.Error)
            ?.takeIf { SchemaErrorDetector.isSchemaError(it.error) }
            ?.let { it.location to it.error }

    when (val state = databaseState) {
        is AppDatabaseState.Loaded -> {
            LaunchedEffect(state.database) {
                onDatabaseReady(state.database, state.location)
                // Capture the "everything synced" baseline at session start for cloud-backed databases.
                if (remoteController?.hasActiveSession() == true && !remoteController.hasSyncBaseline()) {
                    remoteController.markSynced(state.database)
                }
            }
            MoneyManagerApp(
                appVersion = appVersion,
                databaseLocation = state.location,
                services = state.services,
                remoteController = remoteController,
                database = state.database,
                onRequestSwitchDatabase = { target ->
                    scope.launch {
                        switchDatabase(
                            target = target,
                            currentState = databaseState,
                            databaseManager = databaseManager,
                            createAppServices = createAppServices,
                            databaseStateUpdater = { databaseState = it },
                            localSettings = localSettings,
                            onInfoLog = onInfoLog,
                            onErrorLog = onErrorLog,
                            onSwitchFailed = { location, error -> switchError = location to error },
                        )
                    }
                },
                // Re-hydrate the bound cloud database in place. Unlike a switch (different file), this
                // overwrites the *currently open* working copy, so the live connection must be closed
                // first — otherwise SQLite reports the database as busy/locked (notably on Android).
                onReloadFromRemote = {
                    val controller = remoteController
                    if (controller != null) {
                        // Disable the download/upload actions immediately (before the loading screen takes
                        // over), so a second click can't kick off a concurrent reload.
                        controller.beginBusy()
                        scope.launch {
                            val loaded = databaseState as? AppDatabaseState.Loaded ?: return@launch
                            databaseState =
                                AppDatabaseState.Loading(DatabaseInitializationProgress("Downloading from cloud…", 0, 100))
                            onDatabaseReady(null, null)
                            try {
                                // Release the file lock so restore can overwrite the working copy. If the
                                // close fails we must NOT proceed — restoring over a still-open handle is the
                                // file-busy case this flow exists to avoid — so let it abort into the catch.
                                loaded.database.close()
                                val location =
                                    controller.download { progress ->
                                        databaseState =
                                            AppDatabaseState.Loading(
                                                DatabaseInitializationProgress(
                                                    progress.message,
                                                    (progress.fraction * 100).toInt(),
                                                    100,
                                                ),
                                            )
                                    }
                                val error =
                                    openAndLoad(
                                        location = location,
                                        databaseManager = databaseManager,
                                        createAppServices = createAppServices,
                                        databaseStateUpdater = { databaseState = it },
                                        onInfoLog = onInfoLog,
                                        onErrorLog = onErrorLog,
                                    )
                                if (error == null) localSettings.putString(KEY_LAST_DATABASE, location.toString())
                            } catch (expected: CancellationException) {
                                throw expected
                            } catch (expected: Exception) {
                                onErrorLog("Failed to download database from cloud", expected)
                                databaseState = AppDatabaseState.Error(loaded.location, expected)
                            }
                        }
                    }
                },
            )
        }
        is AppDatabaseState.Loading -> DatabaseStartupProgressScreen(state.progress)
        is AppDatabaseState.Error -> {
            // Schema errors are handled by the recovery dialog below; show other failures so the
            // user isn't left on a blank screen.
            if (!SchemaErrorDetector.isSchemaError(state.error)) {
                MinimalErrorScreen(
                    message =
                        "Failed to open database:\n${state.location}\n\n" +
                            (state.error.message ?: state.error::class.simpleName.orEmpty()),
                    stackTrace = state.error.stackTraceToString(),
                )
            }
        }
    }

    switchError?.let { (location, error) ->
        AlertDialog(
            onDismissRequest = { switchError = null },
            confirmButton = {
                TextButton(onClick = { switchError = null }) { Text("OK") }
            },
            title = { Text("Couldn't open database") },
            text = { Text("Failed to open:\n$location\n\n${error.message ?: error::class.simpleName}") },
        )
    }

    remoteUnlock?.let { unlock ->
        RemoteDatabaseUnlockDialog(
            state = unlock,
            onUnlock = { password ->
                val binding = remoteController?.activeBinding()
                if (binding != null) {
                    // Close the dialog immediately so the restore progress bar is clearly visible; it is
                    // only reopened (with an error) if the password was wrong or the download failed.
                    remoteUnlock = null
                    scope.launch {
                        databaseState =
                            AppDatabaseState.Loading(DatabaseInitializationProgress("Restoring from cloud…", 0, 100))
                        try {
                            val location =
                                remoteController.restore(binding, password) { progress ->
                                    databaseState =
                                        AppDatabaseState.Loading(
                                            DatabaseInitializationProgress(progress.message, (progress.fraction * 100).toInt(), 100),
                                        )
                                }
                            val error =
                                openAndLoad(
                                    location = location,
                                    databaseManager = databaseManager,
                                    createAppServices = createAppServices,
                                    databaseStateUpdater = { databaseState = it },
                                    onInfoLog = onInfoLog,
                                    onErrorLog = onErrorLog,
                                )
                            if (error == null) {
                                localSettings.putString(KEY_LAST_DATABASE, location.toString())
                            } else {
                                remoteUnlock = unlock.copy(error = error.message ?: "Failed to open database")
                            }
                        } catch (expected: CancellationException) {
                            throw expected
                        } catch (expected: Exception) {
                            onErrorLog("Failed to restore database from cloud", expected)
                            databaseState = AppDatabaseState.Loading(initialDatabaseProgress())
                            remoteUnlock = unlock.copy(error = "Wrong password, or download failed")
                        }
                    }
                }
            },
        )
    }

    effectiveSchemaError?.let { (location, error) ->
        DatabaseSchemaErrorDialog(
            databaseLocation = location.toString(),
            error = error,
            onBackupAndCreateNew = {
                scope.launch {
                    recreateDatabase(
                        location = location,
                        databaseManager = databaseManager,
                        createAppServices = createAppServices,
                        databaseStateUpdater = { databaseState = it },
                        onInfoLog = onInfoLog,
                        onErrorLog = onErrorLog,
                        shouldBackup = true,
                    )
                }
            },
            onDeleteAndCreateNew = {
                scope.launch {
                    recreateDatabase(
                        location = location,
                        databaseManager = databaseManager,
                        createAppServices = createAppServices,
                        databaseStateUpdater = { databaseState = it },
                        onInfoLog = onInfoLog,
                        onErrorLog = onErrorLog,
                        shouldBackup = false,
                    )
                }
            },
        )
    }
}

@Composable
private fun RemoteDatabaseUnlockDialog(
    state: RemoteUnlockState,
    onUnlock: (String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        // Non-dismissible: the local copy was deleted on close, so the password is required to proceed.
        onDismissRequest = {},
        title = { Text(state.prompt) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                state.error?.let { Text(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onUnlock(password) }, enabled = password.isNotEmpty()) {
                Text("Unlock")
            }
        },
    )
}

/**
 * Resolves which database to open on startup: the last-opened one if it still exists, otherwise the
 * platform default.
 */
private suspend fun resolveStartupLocation(
    databaseManager: DatabaseManager,
    localSettings: LocalSettings,
): DbLocation {
    // A corrupted persisted value (e.g. an invalid path) must not crash startup; fall back to default.
    val stored = localSettings.getString(KEY_LAST_DATABASE)?.let { runCatching { dbLocationFromString(it) }.getOrNull() }
    return if (stored != null && databaseManager.databaseExists(stored)) {
        stored
    } else {
        databaseManager.getDefaultLocation()
    }
}

/**
 * Opens [location] (creating + seeding it if missing), builds the application services, and moves the
 * state machine to Loaded. Returns null on success, or the failure cause (with the state left at
 * Error) otherwise.
 */
private suspend fun openAndLoad(
    location: DbLocation,
    databaseManager: DatabaseManager,
    createAppServices: (MoneyManagerDatabaseWrapper) -> AppServices,
    databaseStateUpdater: (AppDatabaseState) -> Unit,
    onInfoLog: (String) -> Unit,
    onErrorLog: (String, Throwable) -> Unit,
): Throwable? =
    try {
        onInfoLog("Opening database at: $location")
        val database =
            databaseManager.openDatabaseWithProgress(location) { progress ->
                databaseStateUpdater(AppDatabaseState.Loading(progress))
            }
        databaseStateUpdater(AppDatabaseState.Loading(DatabaseInitializationProgress("Preparing application services...", 1, 1)))
        val services = createAppServices(database)
        databaseStateUpdater(AppDatabaseState.Loading(DatabaseInitializationProgress("Verifying this device...", 1, 1)))
        services.deviceId
        databaseStateUpdater(AppDatabaseState.Loaded(location, services, database))
        onInfoLog("Database opened successfully")
        null
    } catch (expected: CancellationException) {
        throw expected
    } catch (expected: Exception) {
        onErrorLog("Failed to open database: ${expected.message}", expected)
        databaseStateUpdater(AppDatabaseState.Error(location, expected))
        expected
    }

/**
 * Switches the live database to [target], rebinding the whole UI to a fresh set of services.
 * A schema-incompatible target is left in the Error state so the recovery dialog can engage; any
 * other failure reverts to the previously open database so the user is never stranded.
 */
private suspend fun switchDatabase(
    target: DbLocation,
    currentState: AppDatabaseState,
    databaseManager: DatabaseManager,
    createAppServices: (MoneyManagerDatabaseWrapper) -> AppServices,
    databaseStateUpdater: (AppDatabaseState) -> Unit,
    localSettings: LocalSettings,
    onInfoLog: (String) -> Unit,
    onErrorLog: (String, Throwable) -> Unit,
    onSwitchFailed: (DbLocation, Throwable) -> Unit,
) {
    if ((currentState as? AppDatabaseState.Loaded)?.location == target) return

    onInfoLog("Switching database to: $target")
    databaseStateUpdater(AppDatabaseState.Loading())
    val error =
        openAndLoad(
            location = target,
            databaseManager = databaseManager,
            createAppServices = createAppServices,
            databaseStateUpdater = databaseStateUpdater,
            onInfoLog = onInfoLog,
            onErrorLog = onErrorLog,
        )
    when {
        error == null -> {
            GlobalSchemaErrorState.clearError()
            localSettings.putString(KEY_LAST_DATABASE, target.toString())
        }
        // Schema-incompatible targets fall through to the schema-error recovery dialog (state stays Error).
        SchemaErrorDetector.isSchemaError(error) -> Unit
        // Any other failure: surface it and revert so the user keeps working in the current database.
        currentState is AppDatabaseState.Loaded -> {
            onInfoLog("Reverting to previously open database: ${currentState.location}")
            databaseStateUpdater(currentState)
            onSwitchFailed(target, error)
        }
        else -> onSwitchFailed(target, error)
    }
}

private suspend fun recreateDatabase(
    location: DbLocation,
    databaseManager: DatabaseManager,
    createAppServices: (MoneyManagerDatabaseWrapper) -> AppServices,
    databaseStateUpdater: (AppDatabaseState) -> Unit,
    onInfoLog: (String) -> Unit,
    onErrorLog: (String, Throwable) -> Unit,
    shouldBackup: Boolean,
) {
    try {
        if (shouldBackup) {
            onInfoLog("Backing up database and creating new one...")
            val backupLocation = databaseManager.backupDatabase(location)
            onInfoLog("Database backed up to: $backupLocation")
        } else {
            onInfoLog("Deleting database and creating new one...")
            databaseManager.deleteDatabase(location)
            onInfoLog("Database deleted")
        }

        val database = databaseManager.openDatabase(location)
        val services = createAppServices(database)
        databaseStateUpdater(AppDatabaseState.Loaded(location, services, database))
        GlobalSchemaErrorState.clearError()
        onInfoLog("New database created successfully")
    } catch (expected: CancellationException) {
        throw expected
    } catch (expected: Exception) {
        val message =
            if (shouldBackup) {
                "Failed to backup and create new database"
            } else {
                "Failed to delete and create new database"
            }
        onErrorLog(message, expected)
        databaseStateUpdater(AppDatabaseState.Error(location, expected))
    }
}
