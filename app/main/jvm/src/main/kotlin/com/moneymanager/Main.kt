package com.moneymanager

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.di.AppComponent
import com.moneymanager.di.AppComponentParams
import com.moneymanager.di.database.DatabaseComponent
import com.moneymanager.di.database.toApplication
import com.moneymanager.remotestorage.sync.RemoteDatabaseController
import com.moneymanager.ui.AppStartupHost
import com.moneymanager.ui.error.GlobalSchemaErrorState
import com.moneymanager.ui.error.SchemaErrorDetector
import com.moneymanager.ui.toAppServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
    val remoteController =
        remember {
            RemoteDatabaseController(component.remoteDatabaseSyncService, component.remoteStorageProviderFactory)
        }
    // The currently open database, tracked so we can push it to remote storage on app close.
    val openDatabase = remember { arrayOfNulls<MoneyManagerDatabaseWrapper>(1) }

    Window(
        onCloseRequest = {
            // Upload the latest database to its remote backing (if any) before exiting.
            openDatabase[0]?.let { database ->
                if (remoteController.hasActiveSession()) {
                    runCatching { runBlocking { remoteController.syncNow(database) } }
                        .onFailure { logger.error(it) { "Failed to sync database on close" } }
                }
            }
            onExit()
        },
        title = "Money Manager",
        state = rememberWindowState(width = 1000.dp, height = 900.dp),
    ) {
        AppStartupHost(
            databaseManager = databaseManager,
            appVersion = appVersion,
            localSettings = localSettings,
            createAppServices = { database ->
                DatabaseComponent.create(database).toApplication().toAppServices()
            },
            onInfoLog = { message -> logger.info { message } },
            onErrorLog = { message, error -> logger.error(error) { message } },
            remoteController = remoteController,
            onDatabaseReady = { database -> openDatabase[0] = database },
        )
    }
}
