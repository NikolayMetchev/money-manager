package com.moneymanager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.moneymanager.credentialvault.CredentialVault
import com.moneymanager.database.DatabaseManager
import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.CryptoCatalogRefresher
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.importfilesource.DriveFolderBrowser
import com.moneymanager.importfilesource.ImportFileSourceFactory
import com.moneymanager.remotestorage.RemoteAuthException
import com.moneymanager.remotestorage.sync.CredentialSyncController
import com.moneymanager.remotestorage.sync.RemoteDatabaseController
import com.moneymanager.remotestorage.sync.StrategySyncController
import com.moneymanager.session.DatabaseSession
import com.moneymanager.session.DatabaseSessions
import com.moneymanager.session.StartupTarget
import com.moneymanager.strategycatalog.StrategyCatalogController
import com.moneymanager.tasks.Progress
import com.moneymanager.ui.components.DatabaseProgressScreen
import com.moneymanager.ui.components.DatabaseSchemaErrorDialog
import com.moneymanager.ui.error.GlobalSchemaErrorState
import com.moneymanager.ui.error.ProvideSchemaAwareScope
import com.moneymanager.ui.error.SchemaErrorDetector
import com.moneymanager.ui.foundation.CredentialVaultPromptHost
import com.moneymanager.ui.foundation.LocalCredentialVault
import com.moneymanager.ui.screens.FirstRunDatabaseSetupScreen
import com.moneymanager.ui.screens.settings.CredentialBackupSyncHost
import com.moneymanager.ui.screens.settings.LocalCredentialSyncController
import com.moneymanager.ui.util.onEnterKeyDown
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private sealed class AppDatabaseState {
    data class Loading(
        val progress: Progress = initialDatabaseProgress(),
    ) : AppDatabaseState()

    data class Loaded(
        val session: DatabaseSession,
    ) : AppDatabaseState() {
        val location: DbLocation get() = session.location
        val database: MoneyManagerDatabaseWrapper get() = session.database
    }

    data class Error(
        val location: DbLocation,
        val error: Throwable,
    ) : AppDatabaseState()

    /** Fresh run: no remembered database and no remote binding, so the user must choose one. */
    data object ChoosingDatabase : AppDatabaseState()
}

private data class RemoteUnlockState(
    val prompt: String,
    val error: String? = null,
    // True when the restore failed because the cloud connection (refresh token) expired/was revoked:
    // the password is fine, but the user must re-run Google consent before the download can succeed.
    val needsReconnect: Boolean = false,
    // The password already entered this run, retained so the post-reconnect retry needn't re-prompt.
    val password: String = "",
)

private fun initialDatabaseProgress() = Progress.counted("Finding the default database...", 0, 1)

@Composable
fun AppStartupHost(
    databaseManager: DatabaseManager,
    appVersion: AppVersion,
    sessions: DatabaseSessions,
    onInfoLog: (String) -> Unit,
    onErrorLog: (String, Throwable) -> Unit,
    remoteController: RemoteDatabaseController? = null,
    strategySyncController: StrategySyncController? = null,
    strategyCatalogController: StrategyCatalogController? = null,
    importFileSourceFactory: ImportFileSourceFactory? = null,
    driveFolderBrowser: DriveFolderBrowser? = null,
    cryptoCatalogRefresher: CryptoCatalogRefresher? = null,
    credentialVault: CredentialVault? = null,
    credentialSyncController: CredentialSyncController? = null,
    onDatabaseReady: (MoneyManagerDatabaseWrapper?, DbLocation?) -> Unit = { _, _ -> },
) {
    val vault = credentialVault ?: LocalCredentialVault.current
    CompositionLocalProvider(
        LocalCredentialVault provides vault,
        LocalCredentialSyncController provides credentialSyncController,
    ) {
        AppStartupContent(
            databaseManager = databaseManager,
            appVersion = appVersion,
            sessions = sessions,
            onInfoLog = onInfoLog,
            onErrorLog = onErrorLog,
            remoteController = remoteController,
            strategySyncController = strategySyncController,
            strategyCatalogController = strategyCatalogController,
            importFileSourceFactory = importFileSourceFactory,
            driveFolderBrowser = driveFolderBrowser,
            cryptoCatalogRefresher = cryptoCatalogRefresher,
            onDatabaseReady = onDatabaseReady,
        )
        // Mounted over every startup state, so a secret needed before a database is open (restoring a
        // cloud-backed one needs the Google sign-in) can still prompt.
        CredentialVaultPromptHost(vault)
        credentialSyncController?.let { CredentialBackupSyncHost(vault, it) }
    }
}

@Composable
private fun AppStartupContent(
    databaseManager: DatabaseManager,
    appVersion: AppVersion,
    sessions: DatabaseSessions,
    onInfoLog: (String) -> Unit,
    onErrorLog: (String, Throwable) -> Unit,
    remoteController: RemoteDatabaseController?,
    strategySyncController: StrategySyncController?,
    strategyCatalogController: StrategyCatalogController?,
    importFileSourceFactory: ImportFileSourceFactory?,
    driveFolderBrowser: DriveFolderBrowser?,
    cryptoCatalogRefresher: CryptoCatalogRefresher?,
    onDatabaseReady: (MoneyManagerDatabaseWrapper?, DbLocation?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var databaseState by remember { mutableStateOf<AppDatabaseState>(AppDatabaseState.Loading()) }
    var switchError by remember { mutableStateOf<Pair<DbLocation, Throwable>?>(null) }
    var remoteUnlock by remember { mutableStateOf<RemoteUnlockState?>(null) }

    suspend fun load(
        location: DbLocation,
        remoteCache: Boolean = false,
    ): Throwable? =
        openAndLoad(
            location = location,
            remoteCache = remoteCache,
            sessions = sessions,
            databaseStateUpdater = { databaseState = it },
            onErrorLog = onErrorLog,
        )

    LaunchedEffect(Unit) {
        when (val target = sessions.resolveStartup()) {
            is StartupTarget.Open -> {
                val error = load(target.location, target.remoteCache)
                // The kept working copy is unusable (corrupt/unreadable): recover from the encrypted remote copy.
                val binding = remoteController?.activeBinding()
                if (error != null && target.remoteCache && binding != null) {
                    remoteUnlock = RemoteUnlockState(prompt = "Unlock “${binding.remoteName}” from cloud storage")
                }
            }
            // No (usable) local copy — first run on this device, or the user chose not to keep it: the
            // encrypted remote copy is all we have, so restore it, which requires the user's password first.
            is StartupTarget.NeedsRemoteUnlock ->
                remoteUnlock = RemoteUnlockState(prompt = "Unlock “${target.binding.remoteName}” from cloud storage")
            StartupTarget.ChooseDatabase -> databaseState = AppDatabaseState.ChoosingDatabase
        }
    }

    val globalSchemaError by GlobalSchemaErrorState.schemaError.collectAsState()
    // Guards against overlapping recreate runs: multiple schema-error flows (or a double-click) could
    // otherwise each delete the database, so a second delete wipes the schema the first just created,
    // making its seed fail with "no such table". Only one recreate runs at a time.
    var isRecreatingDatabase by remember { mutableStateOf(false) }
    val effectiveSchemaError: Pair<DbLocation, Throwable>? =
        globalSchemaError
            ?.takeIf { SchemaErrorDetector.isSchemaError(it) }
            ?.let { error ->
                val location =
                    (databaseState as? AppDatabaseState.Loaded)?.location
                        ?: databaseManager.getDefaultLocation()
                location to error
            } ?: (databaseState as? AppDatabaseState.Error)
            ?.takeIf { SchemaErrorDetector.isSchemaError(it.error) }
            ?.let { it.location to it.error }

    when (val state = databaseState) {
        is AppDatabaseState.Loaded -> {
            LaunchedEffect(state.database) {
                onDatabaseReady(state.database, state.location)
            }
            MoneyManagerApp(
                appVersion = appVersion,
                databaseLocation = state.location,
                services = state.session.services,
                remoteController = remoteController,
                database = state.database,
                strategySyncController = strategySyncController,
                strategyCatalogController = strategyCatalogController,
                importFileSourceFactory = importFileSourceFactory,
                driveFolderBrowser = driveFolderBrowser,
                cryptoCatalogRefresher = cryptoCatalogRefresher,
                onRequestSwitchDatabase = { target ->
                    scope.launch {
                        switchDatabase(
                            target = target,
                            currentState = databaseState,
                            sessions = sessions,
                            databaseStateUpdater = { databaseState = it },
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
                    if (remoteController != null) {
                        // Disable the download/upload actions immediately (before the loading screen takes
                        // over), so a second click can't kick off a concurrent reload.
                        remoteController.beginBusy()
                        scope.launch {
                            val loaded = databaseState as? AppDatabaseState.Loaded ?: return@launch
                            databaseState =
                                AppDatabaseState.Loading(Progress.counted("Downloading from cloud…", 0, 100))
                            onDatabaseReady(null, null)
                            try {
                                databaseState =
                                    AppDatabaseState.Loaded(
                                        sessions.reloadFromRemote(loaded.session) { progress ->
                                            databaseState = AppDatabaseState.Loading(progress)
                                        },
                                    )
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
        is AppDatabaseState.Loading -> DatabaseProgressScreen(state.progress, title = "Starting Money Manager")
        is AppDatabaseState.ChoosingDatabase ->
            // The setup screen (and the Google Drive dialog it reuses) need a schema-aware scope, which
            // MoneyManagerApp only provides once a database is loaded — so provide one here too.
            ProvideSchemaAwareScope {
                FirstRunDatabaseSetupScreen(
                    databaseManager = databaseManager,
                    remoteController = remoteController,
                    sessions = sessions,
                    onLocalReady = { location -> scope.launch { load(location) } },
                    onRemoteOpened = { location -> scope.launch { load(location) } },
                    // createRemote already opened, seeded, uploaded and bound the database, so go straight to Loaded.
                    onRemoteCreated = { session -> databaseState = AppDatabaseState.Loaded(session) },
                    onErrorLog = onErrorLog,
                )
            }
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
        // The dialog only shows for a remote-backed database, so a controller is always present here.
        val controller = remoteController ?: return@let
        val binding = controller.activeBinding()

        // Hydrates the bound database with [password]. A failed Google connection (expired/revoked refresh
        // token) is surfaced distinctly from a bad password so the dialog can offer to re-run consent
        // rather than wrongly blaming the password.
        suspend fun restoreWithPassword(password: String) {
            if (binding == null) return
            databaseState =
                AppDatabaseState.Loading(Progress.counted("Restoring from cloud…", 0, 100))
            try {
                val location =
                    controller.restore(binding, password) { progress ->
                        databaseState = AppDatabaseState.Loading(progress)
                    }
                val error = load(location)
                if (error != null) {
                    // A non-auth failure: leave reconnect mode so the password field returns.
                    remoteUnlock =
                        unlock.copy(needsReconnect = false, password = "", error = error.message ?: "Failed to open database")
                }
            } catch (expected: CancellationException) {
                throw expected
            } catch (authExpired: RemoteAuthException) {
                onErrorLog("Cloud authentication expired while restoring database", authExpired)
                databaseState = AppDatabaseState.Loading(initialDatabaseProgress())
                remoteUnlock =
                    unlock.copy(
                        needsReconnect = true,
                        password = password,
                        error = "Your Google Drive connection has expired or was revoked.",
                    )
            } catch (expected: Exception) {
                onErrorLog("Failed to restore database from cloud", expected)
                databaseState = AppDatabaseState.Loading(initialDatabaseProgress())
                // A non-auth failure (e.g. wrong password): leave reconnect mode so the password field returns.
                remoteUnlock = unlock.copy(needsReconnect = false, password = "", error = "Wrong password, or download failed")
            }
        }

        RemoteDatabaseUnlockDialog(
            state = unlock,
            onUnlock = { password ->
                if (binding != null) {
                    // Close the dialog immediately so the restore progress bar is clearly visible; it is
                    // only reopened (with an error) if the password was wrong or the download failed.
                    remoteUnlock = null
                    scope.launch { restoreWithPassword(password) }
                }
            },
            onReconnect = {
                if (binding != null) {
                    remoteUnlock = null
                    scope.launch {
                        databaseState =
                            AppDatabaseState.Loading(
                                Progress.counted("Reconnecting to ${binding.remoteName}…", 0, 100),
                            )
                        try {
                            // Full interactive consent mints a fresh refresh token; then retry the restore
                            // with the password the user already supplied (held on the dialog state).
                            controller.reconnect(binding.providerId, binding.providerConfig)
                            restoreWithPassword(unlock.password)
                        } catch (expected: CancellationException) {
                            throw expected
                        } catch (expected: Exception) {
                            onErrorLog("Failed to reconnect to cloud storage", expected)
                            databaseState = AppDatabaseState.Loading(initialDatabaseProgress())
                            remoteUnlock =
                                unlock.copy(
                                    needsReconnect = true,
                                    error = "Reconnect failed: ${expected.message ?: "could not sign in"}",
                                )
                        }
                    }
                }
            },
        )
    }

    effectiveSchemaError?.let { (location, error) ->
        // Hide the dialog while a recreate is running so its buttons can't fire a second, racing delete.
        if (!isRecreatingDatabase) {
            DatabaseSchemaErrorDialog(
                databaseLocation = location.toString(),
                error = error,
                onBackupAndCreateNew = {
                    // Guard against a fast double-click queuing a second recreate before recomposition
                    // hides the dialog (overlapping delete/recreate would corrupt the fresh schema).
                    if (!isRecreatingDatabase) {
                        isRecreatingDatabase = true
                        scope.launch {
                            try {
                                recreateDatabase(
                                    location = location,
                                    sessions = sessions,
                                    databaseStateUpdater = { databaseState = it },
                                    onInfoLog = onInfoLog,
                                    onErrorLog = onErrorLog,
                                    shouldBackup = true,
                                )
                            } finally {
                                isRecreatingDatabase = false
                            }
                        }
                    }
                },
                onDeleteAndCreateNew = {
                    if (!isRecreatingDatabase) {
                        isRecreatingDatabase = true
                        scope.launch {
                            try {
                                recreateDatabase(
                                    location = location,
                                    sessions = sessions,
                                    databaseStateUpdater = { databaseState = it },
                                    onInfoLog = onInfoLog,
                                    onErrorLog = onErrorLog,
                                    shouldBackup = false,
                                )
                            } finally {
                                isRecreatingDatabase = false
                            }
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun RemoteDatabaseUnlockDialog(
    state: RemoteUnlockState,
    onUnlock: (String) -> Unit,
    onReconnect: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    val passwordFocusRequester = remember { FocusRequester() }
    LaunchedEffect(state.needsReconnect) {
        if (!state.needsReconnect) runCatching { passwordFocusRequester.requestFocus() }
    }
    AlertDialog(
        // Non-dismissible: the local copy was deleted on close, so the password is required to proceed.
        onDismissRequest = {},
        title = { Text(state.prompt) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.needsReconnect) {
                    // The password is already known to be correct; only the cloud sign-in needs renewing,
                    // so prompt for re-consent instead of showing the password field again.
                    Text("Reconnect to your Google account to finish restoring this database.")
                } else {
                    val submit = { if (password.isNotEmpty()) onUnlock(password) }
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.focusRequester(passwordFocusRequester).onEnterKeyDown(submit),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                    )
                }
                state.error?.let { Text(it) }
            }
        },
        confirmButton = {
            if (state.needsReconnect) {
                TextButton(onClick = onReconnect) { Text("Reconnect to Google Drive") }
            } else {
                TextButton(onClick = { onUnlock(password) }, enabled = password.isNotEmpty()) {
                    Text("Unlock")
                }
            }
        },
    )
}

/**
 * Opens [location] through [sessions] and moves the state machine to Loaded. Returns null on success,
 * or the failure cause (with the state left at Error) otherwise.
 */
private suspend fun openAndLoad(
    location: DbLocation,
    remoteCache: Boolean = false,
    sessions: DatabaseSessions,
    databaseStateUpdater: (AppDatabaseState) -> Unit,
    onErrorLog: (String, Throwable) -> Unit,
): Throwable? =
    try {
        val session =
            sessions.open(location, remoteCache) { progress ->
                databaseStateUpdater(AppDatabaseState.Loading(progress))
            }
        databaseStateUpdater(AppDatabaseState.Loaded(session))
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
    sessions: DatabaseSessions,
    databaseStateUpdater: (AppDatabaseState) -> Unit,
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
            sessions = sessions,
            databaseStateUpdater = databaseStateUpdater,
            onErrorLog = onErrorLog,
        )
    when {
        error == null -> GlobalSchemaErrorState.clearError()
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
    sessions: DatabaseSessions,
    databaseStateUpdater: (AppDatabaseState) -> Unit,
    onInfoLog: (String) -> Unit,
    onErrorLog: (String, Throwable) -> Unit,
    shouldBackup: Boolean,
) {
    try {
        databaseStateUpdater(AppDatabaseState.Loaded(sessions.recreate(location, backup = shouldBackup)))
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
