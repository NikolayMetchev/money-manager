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
import co.touchlab.kermit.Logger
import com.moneymanager.cryptodata.HttpCryptoCatalogRefresher
import com.moneymanager.cryptodata.installCryptoCatalog
import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.di.AppComponent
import com.moneymanager.di.params.AppComponentParams
import com.moneymanager.importfilesource.di.createDriveFolderBrowser
import com.moneymanager.importfilesource.di.createImportFileSourceFactory
import com.moneymanager.session.CloseDecision
import com.moneymanager.session.DatabaseSessions
import com.moneymanager.session.closeDecision
import com.moneymanager.session.performClose
import com.moneymanager.tasks.Progress
import com.moneymanager.ui.AppStartupHost
import com.moneymanager.ui.CloseDatabaseDialog
import com.moneymanager.ui.components.DatabaseProgressScreen
import com.moneymanager.ui.error.GlobalSchemaErrorState
import com.moneymanager.ui.error.SchemaErrorDetector
import com.moneymanager.ui.foundation.CredentialVaultPromptHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing

private val logger = Logger.withTag("Main")

fun main() {
    Logger.setLogWriters(Slf4jLogWriter())
    logger.i { "Starting Money Manager application" }

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
            logger.e(throwable) { "Schema error detected: ${throwable.message}" }
            GlobalSchemaErrorState.reportError(throwable)
        } else {
            // Delegate to default handler for non-schema errors
            logger.e(throwable) { "Uncaught exception on thread ${thread.name}: ${throwable.message}" }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    // Install the bundled crypto-asset name catalog (+ any network-refreshed layer) before imports run.
    // This auxiliary feature must not block startup, so a failure is logged rather than propagated.
    runCatching { installCryptoCatalog() }
        .onFailure { logger.e(it) { "Failed to install crypto catalog" } }

    application {
        MainWindow(onExit = ::exitApplication)
    }
}

@Composable
@Suppress("FunctionName")
private fun MainWindow(onExit: () -> Unit) {
    // Initialize DI component once
    val params = remember { AppComponentParams() }
    val component =
        remember {
            AppComponent.create(params).also {
                logger.i { "DI component created successfully" }
            }
        }

    val databaseManager = component.databaseManager
    val appVersion = component.appVersion
    val localSettings = component.localSettings
    val remoteController = component.remoteDatabaseController
    val sessions =
        remember { DatabaseSessions(databaseManager, localSettings, component.credentialVault, remoteController) }
    val importFileSourceFactory = createImportFileSourceFactory(params, component.credentialVault)
    val driveFolderBrowser = createDriveFolderBrowser(params, component.credentialVault)
    // The currently open database, tracked so we can push it and clean up on app close.
    val openDatabase = remember { arrayOfNulls<MoneyManagerDatabaseWrapper>(1) }
    val scope = rememberCoroutineScope()
    // Non-null while the closing sync (shrink → encrypt → upload) is running; drives the progress UI.
    var closeProgress by remember { mutableStateOf<Progress?>(null) }
    // Non-null while the close dialog (upload / keep-local tickboxes) is shown for a cloud-backed database.
    var closeDecision by remember { mutableStateOf<CloseDecision?>(null) }

    Window(
        onCloseRequest = {
            val database = openDatabase[0]
            val cloudBacked = remoteController.activeBinding() != null
            logger.i { "Window close: database=${database != null}, cloudBacked=$cloudBacked" }
            when {
                closeProgress != null -> Unit // a close sync is already in progress; ignore repeat clicks
                closeDecision != null -> Unit // the close dialog is already open
                database == null || !cloudBacked -> onExit()
                else ->
                    // Decide the dialog's defaults (is the local copy dirty? do we still need a password?)
                    // off the close click, then show the tickbox dialog.
                    scope.launch { closeDecision = remoteController.closeDecision(database) }
            }
        },
        title = "Money Manager",
        state = rememberWindowState(width = 1000.dp, height = 900.dp),
    ) {
        val progress = closeProgress
        if (progress != null) {
            DatabaseProgressScreen(progress, title = "Closing Money Manager")
            // The closing upload needs the Google sign-in from the credential vault, and AppStartupHost (which
            // normally hosts the unlock prompt) isn't composed while this progress screen is up.
            CredentialVaultPromptHost(component.credentialVault)
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
                            closeProgress = Progress.counted("Finishing…", 0, 100)
                            scope.launch {
                                remoteController.performClose(database, upload, keepLocal, password, decision.localChanged) {
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
                sessions = sessions,
                onInfoLog = { message -> logger.i { message } },
                onErrorLog = { message, error -> logger.e(error) { message } },
                remoteController = remoteController,
                strategySyncController = component.strategySyncController,
                strategyCatalogController = component.strategyCatalogController,
                importFileSourceFactory = importFileSourceFactory,
                driveFolderBrowser = driveFolderBrowser,
                cryptoCatalogRefresher = HttpCryptoCatalogRefresher(),
                credentialVault = component.credentialVault,
                credentialSyncController = component.credentialSyncController,
                onDatabaseReady = { database, _ -> openDatabase[0] = database },
            )
        }
    }
}
