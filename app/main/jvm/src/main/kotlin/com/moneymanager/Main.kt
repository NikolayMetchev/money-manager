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
import com.moneymanager.di.database.toApplication
import com.moneymanager.importengineapi.EditingLockedException
import com.moneymanager.remotestorage.sync.SyncResult
import com.moneymanager.ui.AppStartupHost
import com.moneymanager.ui.components.DatabaseStartupProgressScreen
import com.moneymanager.ui.error.GlobalSchemaErrorState
import com.moneymanager.ui.error.SchemaErrorDetector
import com.moneymanager.ui.toAppServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import org.lighthousegames.logging.logging

private val logger = logging()

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
    // The currently open database, tracked so we can push it and clean up on app close.
    val openDatabase = remember { arrayOfNulls<MoneyManagerDatabaseWrapper>(1) }
    val scope = rememberCoroutineScope()
    // Non-null while the closing sync (shrink → encrypt → upload) is running; drives the progress UI.
    var closeProgress by remember { mutableStateOf<DatabaseInitializationProgress?>(null) }

    Window(
        onCloseRequest = {
            val database = openDatabase[0]
            val hasSession = remoteController.hasActiveSession()
            logger.info { "Window close: database=${database != null}, cloudSession=$hasSession" }
            when {
                closeProgress != null -> Unit // a close sync is already in progress; ignore repeat clicks
                database == null || !hasSession -> onExit()
                else -> {
                    // Upload the latest database only if it changed (with a progress screen), then delete
                    // the local working copy: when cloud storage is in use only the encrypted remote copy
                    // is kept between runs.
                    closeProgress = DatabaseInitializationProgress("Checking for changes…", 0, 100)
                    scope.launch {
                        // If we can't tell, assume changed and upload to be safe.
                        val changed = runCatching { remoteController.hasUnsyncedChanges(database) }.getOrDefault(true)
                        val remoteUpToDate =
                            if (changed) {
                                // Guarded push: if another device pushed since our last sync the upload is
                                // refused (BLOCKED) rather than clobbering it — then we keep the local copy.
                                runCatching {
                                    remoteController.syncNow(database, rebuildViews = false) { progress ->
                                        closeProgress =
                                            DatabaseInitializationProgress(progress.message, (progress.fraction * 100).toInt(), 100)
                                    }
                                }.onFailure { logger.error(it) { "Failed to sync database on close" } }
                                    .getOrNull() == SyncResult.UPLOADED
                            } else {
                                logger.info { "No changes since last sync; skipping upload on close" }
                                true
                            }
                        closeProgress = DatabaseInitializationProgress("Finishing…", 95, 100)
                        database.close()
                        if (remoteUpToDate) {
                            runCatching { remoteController.deleteLocalCache() }
                                .onSuccess { logger.info { "Deleted local working copy; cloud copy is up to date" } }
                                .onFailure { logger.error(it) { "Failed to delete local database on close" } }
                        } else {
                            logger.warn { "Kept local database: cloud sync failed, so the local copy is the only safe copy" }
                        }
                        onExit()
                    }
                }
            }
        },
        title = "Money Manager",
        state = rememberWindowState(width = 1000.dp, height = 900.dp),
    ) {
        val progress = closeProgress
        if (progress != null) {
            DatabaseStartupProgressScreen(progress)
        } else {
            AppStartupHost(
                databaseManager = databaseManager,
                appVersion = appVersion,
                localSettings = localSettings,
                createAppServices = { database ->
                    DatabaseComponent.create(database).toApplication().toAppServices(
                        editGate = {
                            if (remoteController.syncState.value.editingLocked) throw EditingLockedException()
                        },
                    )
                },
                onInfoLog = { message -> logger.info { message } },
                onErrorLog = { message, error -> logger.error(error) { message } },
                remoteController = remoteController,
                onDatabaseReady = { database, _ -> openDatabase[0] = database },
            )
        }
    }
}
