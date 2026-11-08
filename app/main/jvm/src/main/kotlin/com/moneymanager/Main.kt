package com.moneymanager

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import co.touchlab.kermit.Logger
import com.moneymanager.browser.BrowserLaunchException
import com.moneymanager.browser.BrowserLauncher
import com.moneymanager.cryptodata.HttpCryptoCatalogRefresher
import com.moneymanager.cryptodata.installCryptoCatalog
import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.di.AppComponent
import com.moneymanager.di.params.AppComponentParams
import com.moneymanager.importfilesource.di.createDriveFolderBrowser
import com.moneymanager.importfilesource.di.createImportFileSourceFactory
import com.moneymanager.presenter.error.GlobalSchemaErrorState
import com.moneymanager.presenter.error.SchemaErrorDetector
import com.moneymanager.session.CloseDecision
import com.moneymanager.session.DatabaseSessions
import com.moneymanager.session.closeDecision
import com.moneymanager.session.performClose
import com.moneymanager.tasks.Progress
import com.moneymanager.ui.AppStartupHost
import com.moneymanager.ui.CloseDatabaseDialog
import com.moneymanager.ui.components.DatabaseProgressScreen
import com.moneymanager.ui.foundation.CredentialVaultPromptHost
import com.moneymanager.ui.foundation.LocalStartupProbe
import com.moneymanager.ui.foundation.StartupMilestone
import com.moneymanager.ui.util.setPlainText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlin.system.exitProcess

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

    // Not System.exit while the window is still being torn down: exit runs at a safepoint, and libGLX's unload
    // hook then waits for the X display lock, which AWT's X thread can be holding while its error handler (an X
    // error from the window just destroyed) waits for that safepoint — the app hung on close. Returning instead
    // lets the JVM end once AWT has shut itself down, by which point no X call is in flight.
    application(exitProcessOnExit = false) {
        MainWindow(onExit = ::exitApplication)
    }
    logger.i { "Window closed; waiting for AWT to shut down" }
    exitIfStillRunningAfterGracePeriod()
}

private const val EXIT_GRACE_MILLIS = 10_000L

/** A non-daemon thread (an HTTP client's pool, say) would keep the JVM up; by now AWT is quiet, so exit is safe. */
private fun exitIfStillRunningAfterGracePeriod() {
    Thread {
        Thread.sleep(EXIT_GRACE_MILLIS)
        logger.w { "Still running ${EXIT_GRACE_MILLIS}ms after the window closed; exiting" }
        exitProcess(0)
    }.apply {
        isDaemon = true
        name = "exit-watchdog"
        start()
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
    val startupProbe = remember { DesktopStartupProbe.fromEnvironment(onDone = onExit) }

    Window(
        onCloseRequest = {
            val database = openDatabase[0]
            val cloudBacked = remoteController.activeBinding() != null
            logger.i { "Window close: database=${database != null}, cloudBacked=$cloudBacked" }
            when {
                closeProgress != null -> {}

                // a close sync is already in progress; ignore repeat clicks
                closeDecision != null -> {}

                // the close dialog is already open
                database == null || !cloudBacked -> {
                    onExit()
                }

                else -> {
                    // Decide the dialog's defaults (is the local copy dirty? do we still need a password?)
                    // off the close click, then show the tickbox dialog.
                    scope.launch { closeDecision = remoteController.closeDecision(database) }
                }
            }
        },
        title = "Money Manager",
        state = rememberWindowState(width = 1000.dp, height = 900.dp),
    ) {
        if (startupProbe != null) {
            LaunchedEffect(Unit) {
                withFrameNanos { }
                startupProbe.reached(StartupMilestone.FIRST_FRAME)
            }
        }
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
            // Every link in the app opens through the same launcher as the Google sign-in, so the browser also
            // comes to the front where `xdg-open` alone leaves it behind (Hyprland).
            var unopenedUrl by remember { mutableStateOf<String?>(null) }
            val uriHandler = remember { BrowserUriHandler(params.browserLauncher) { url -> unopenedUrl = url } }
            unopenedUrl?.let { url -> UnopenedLinkDialog(url, onDismiss = { unopenedUrl = null }) }
            CompositionLocalProvider(LocalStartupProbe provides startupProbe, LocalUriHandler provides uriHandler) {
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
}

/** Compose's links, opened by [launcher]; a link no browser opened goes to [onUnopened] rather than throwing into the click. */
private class BrowserUriHandler(
    private val launcher: BrowserLauncher,
    private val onUnopened: (String) -> Unit,
) : UriHandler {
    override fun openUri(uri: String) {
        try {
            launcher.open(uri)
        } catch (failed: BrowserLaunchException) {
            logger.w(failed) { "Couldn't open $uri" }
            onUnopened(uri)
        }
    }
}

/** No browser opened [url], so it is shown for the person to copy and open themselves. */
@Composable
@Suppress("FunctionName")
private fun UnopenedLinkDialog(
    url: String,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Couldn't open a browser") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Open this link in your browser:")
                SelectionContainer { Text(url, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch { clipboard.setPlainText(url) }
                onDismiss()
            }) { Text("Copy link") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
