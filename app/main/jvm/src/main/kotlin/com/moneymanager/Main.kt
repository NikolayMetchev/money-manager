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
import com.moneymanager.domain.model.DbLocation
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
    // The currently open database + its location, tracked so we can push and clean up on app close.
    val openDatabase = remember { arrayOfNulls<MoneyManagerDatabaseWrapper>(1) }
    val openLocation = remember { arrayOfNulls<DbLocation>(1) }

    Window(
        onCloseRequest = {
            val database = openDatabase[0]
            if (database != null && remoteController.hasActiveSession()) {
                // Upload the latest database, then delete the local working copy: when cloud storage is
                // in use only the encrypted remote copy is kept between runs.
                val pushed =
                    runCatching { runBlocking { remoteController.syncNow(database) } }
                        .onFailure { logger.error(it) { "Failed to sync database on close" } }
                        .isSuccess
                database.close()
                if (pushed) {
                    openLocation[0]?.let { location ->
                        runCatching { runBlocking { databaseManager.deleteDatabase(location) } }
                            .onFailure { logger.error(it) { "Failed to delete local database on close" } }
                    }
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
            onDatabaseReady = { database, location ->
                openDatabase[0] = database
                openLocation[0] = location
            },
        )
    }
}
