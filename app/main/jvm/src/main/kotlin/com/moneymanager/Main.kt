package com.moneymanager

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.moneymanager.database.DatabaseInitializationProgress
import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.di.AppComponent
import com.moneymanager.di.AppComponentParams
import com.moneymanager.di.database.DatabaseComponent
import com.moneymanager.di.database.createImportEngine
import com.moneymanager.di.database.toApplication
import com.moneymanager.di.importfilesource.createDriveFolderBrowser
import com.moneymanager.di.importfilesource.createImportFileSourceFactory
import com.moneymanager.importengineapi.EditingLockedException
import com.moneymanager.remotestorage.sync.RemoteDatabaseController
import com.moneymanager.remotestorage.sync.SyncResult
import com.moneymanager.ui.AppStartupHost
import com.moneymanager.ui.CloseDatabaseDialog
import com.moneymanager.ui.components.DatabaseProgressScreen
import com.moneymanager.ui.error.GlobalSchemaErrorState
import com.moneymanager.ui.error.SchemaErrorDetector
import com.moneymanager.ui.toAppServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import org.lighthousegames.logging.logging

private val logger = logging()

/** The defaults computed for the close dialog of a cloud-backed database. */
private data class CloseDecision(
    val remoteName: String,
    val localChanged: Boolean,
    val needsPassword: Boolean,
)

/**
 * Applies the user's close choices for a cloud-backed database: optionally arms a session (when the
 * database was opened straight from a kept local copy, [password] is required) and uploads, then
 * closes the connection and — only when the user chose not to keep the local copy AND the remote is
 * up to date — deletes the local working copy. The local copy is never dropped while it holds
 * unsynced changes.
 */
private suspend fun performClose(
    database: MoneyManagerDatabaseWrapper,
    remoteController: RemoteDatabaseController,
    upload: Boolean,
    keepLocal: Boolean,
    password: String?,
    localChanged: Boolean,
    onProgress: (DatabaseInitializationProgress) -> Unit,
) {
    var remoteCurrent = false
    if (upload) {
        onProgress(DatabaseInitializationProgress("Checking for changes…", 0, 100))
        val ready =
            remoteController.hasActiveSession() ||
                (password != null && runCatching { remoteController.resume(password) }.getOrDefault(false))
        remoteCurrent =
            if (ready) {
                // Guarded push: if another device pushed since our last sync the upload is refused
                // (BLOCKED) rather than clobbering it — then we keep the local copy.
                runCatching {
                    remoteController.syncNow(database, rebuildViews = false) { progress ->
                        onProgress(DatabaseInitializationProgress(progress.message, (progress.fraction * 100).toInt(), 100))
                    }
                }.onFailure { logger.error(it) { "Failed to sync database on close" } }
                    .getOrNull() == SyncResult.UPLOADED
            } else {
                logger.warn { "Could not arm cloud session on close (wrong password?); keeping local copy" }
                false
            }
    } else if (!localChanged && !keepLocal) {
        // About to drop the only local copy without uploading: confirm the remote archive still exists
        // and is reachable first. A "no local changes" baseline doesn't guarantee the remote wasn't
        // deleted externally since our last sync — without this check we'd close with no recoverable copy.
        onProgress(DatabaseInitializationProgress("Checking cloud copy…", 0, 100))
        remoteCurrent =
            runCatching { remoteController.remoteArchiveSize() != null }
                .onFailure { logger.error(it) { "Failed to verify remote copy on close; keeping local copy" } }
                .getOrDefault(false)
    }
    onProgress(DatabaseInitializationProgress("Finishing…", 95, 100))
    database.close()
    when {
        keepLocal -> Unit
        remoteCurrent ->
            runCatching { remoteController.deleteLocalCache() }
                .onSuccess { logger.info { "Deleted local working copy; cloud copy is up to date" } }
                .onFailure { logger.error(it) { "Failed to delete local database on close" } }
        else -> logger.warn { "Kept local database despite delete request: cloud copy is not up to date or unverified" }
    }
}

fun main() {
    logger.info { "Starting Money Manager application" }

    // On Linux, AWT's FileDialog uses an in-process GTK file chooser whose text rendering
    // breaks when the JDK's bundled libfreetype clashes with the system GTK's (blank labels,
    // "drawing failure ... error occurred in libfreetype" warnings). Fall back to AWT's own
    // dialog instead. Must be set before AWT initialises.
    val osName = System.getProperty("os.name").orEmpty().lowercase()
    if (osName.contains("linux")) {
        System.setProperty("sun.awt.disableGtkFileDialogs", "true")
    }

    // Desktop runs need the Swing Main dispatcher provider on the application classpath.
    Dispatchers.Swing

    // Set up global exception handler for schema errors
    val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        if (SchemaErrorDetector.isSchemaError(throwable)) {
            logger.error(throwable) { "Schema error detected: ${throwable.message}" }
            GlobalSchemaErrorState.reportError(
                databaseLocation = "default",
                error = throwable,
            )
        } else {
            // Delegate to default handler for non-schema errors
            logger.error(throwable) { "Uncaught exception on thread ${thread.name}: ${throwable.message}" }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    application {
        MainWindow(onExit = ::exitApplication)
    }
}

@Composable
@Suppress("FunctionName")
private fun MainWindow(onExit: () -> Unit) {
    // Initialize DI component once
    val component =
        remember {
            AppComponent.create(AppComponentParams()).also {
                logger.info { "DI component created successfully" }
            }
        }

    val databaseManager = component.databaseManager
    val appVersion = component.appVersion
    val localSettings = component.localSettings
    val remoteController = component.remoteDatabaseController
    val importFileSourceFactory = createImportFileSourceFactory(localSettings)
    val driveFolderBrowser = createDriveFolderBrowser(localSettings)
    // The currently open database, tracked so we can push it and clean up on app close.
    val openDatabase = remember { arrayOfNulls<MoneyManagerDatabaseWrapper>(1) }
    val scope = rememberCoroutineScope()
    // Non-null while the closing sync (shrink → encrypt → upload) is running; drives the progress UI.
    var closeProgress by remember { mutableStateOf<DatabaseInitializationProgress?>(null) }
    // Non-null while the close dialog (upload / keep-local tickboxes) is shown for a cloud-backed database.
    var closeDecision by remember { mutableStateOf<CloseDecision?>(null) }

    Window(
        onCloseRequest = {
            val database = openDatabase[0]
            val cloudBacked = remoteController.activeBinding() != null
            logger.info { "Window close: database=${database != null}, cloudBacked=$cloudBacked" }
            when {
                closeProgress != null -> Unit // a close sync is already in progress; ignore repeat clicks
                closeDecision != null -> Unit // the close dialog is already open
                database == null || !cloudBacked -> onExit()
                else ->
                    // Decide the dialog's defaults (is the local copy dirty? do we still need a password?)
                    // off the close click, then show the tickbox dialog.
                    scope.launch {
                        val baselineKnown = remoteController.hasSyncBaseline()
                        val changed = runCatching { remoteController.hasUnsyncedChanges(database) }.getOrDefault(true)
                        closeDecision =
                            CloseDecision(
                                remoteName = remoteController.activeBinding()?.remoteName ?: "cloud storage",
                                // Assume changed when we have no baseline, so the upload is offered by default.
                                localChanged = !baselineKnown || changed,
                                needsPassword = !remoteController.hasActiveSession(),
                            )
                    }
            }
        },
        title = "Money Manager",
        state = rememberWindowState(width = 1000.dp, height = 900.dp),
    ) {
        val progress = closeProgress
        if (progress != null) {
            DatabaseProgressScreen(progress, title = "Closing Money Manager")
        } else {
            closeDecision?.let { decision ->
                val database = openDatabase[0]
                CloseDatabaseDialog(
                    remoteName = decision.remoteName,
                    localChanged = decision.localChanged,
                    needsPassword = decision.needsPassword,
                    onCancel = { closeDecision = null },
                    onConfirm = { upload, keepLocal, password ->
                        closeDecision = null
                        if (database == null) {
                            onExit()
                        } else {
                            closeProgress = DatabaseInitializationProgress("Finishing…", 0, 100)
                            scope.launch {
                                performClose(database, remoteController, upload, keepLocal, password, decision.localChanged) {
                                    closeProgress = it
                                }
                                onExit()
                            }
                        }
                    },
                )
            }
            AppStartupHost(
                databaseManager = databaseManager,
                appVersion = appVersion,
                localSettings = localSettings,
                createAppServices = { database ->
                    val component = DatabaseComponent.create(database)
                    val importEngine =
                        component.createImportEngine(
                            editGate = {
                                if (remoteController.syncState.value.editingLocked) throw EditingLockedException()
                            },
                        )
                    component.toApplication().toAppServices(importEngine)
                },
                onInfoLog = { message -> logger.info { message } },
                onErrorLog = { message, error -> logger.error(error) { message } },
                remoteController = remoteController,
                importFileSourceFactory = importFileSourceFactory,
                driveFolderBrowser = driveFolderBrowser,
                onDatabaseReady = { database, _ -> openDatabase[0] = database },
            )
        }
    }
}
