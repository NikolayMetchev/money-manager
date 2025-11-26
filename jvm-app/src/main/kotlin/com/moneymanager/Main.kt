package com.moneymanager

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.moneymanager.database.getDefaultDatabasePath
import com.moneymanager.di.AppComponent
import com.moneymanager.domain.di.AppComponentParams
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.ui.DatabaseSelectionDialog
import com.moneymanager.ui.ErrorDialog
import com.moneymanager.ui.ErrorState
import com.moneymanager.ui.MinimalErrorScreen
import com.moneymanager.ui.MoneyManagerApp
import com.moneymanager.ui.SimpleFallbackErrorScreen
import com.moneymanager.ui.debug.LogCollector
import com.moneymanager.ui.debug.LogLevel
import org.lighthousegames.logging.logging
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path
import java.nio.file.Paths

private val logger = logging()

@Suppress("TooGenericExceptionCaught", "PrintStackTrace")
private fun log(
    level: LogLevel,
    message: String,
    throwable: Throwable? = null,
) {
    try {
        LogCollector.log(level, message, throwable)
        val logMessage = if (throwable != null) "$message: ${throwable.message}" else message
        when (level) {
            LogLevel.DEBUG -> logger.debug { logMessage }
            LogLevel.INFO -> logger.info { logMessage }
            LogLevel.WARN -> logger.warn { logMessage }
            LogLevel.ERROR -> logger.error { logMessage }
        }
        throwable?.let {
            logger.error(it) { "Stack trace" }
            // Also print to console for debugging packaged app
            it.printStackTrace()
        }
    } catch (e: Exception) {
        // Fallback if logging fails
        println("LOGGING ERROR: ${e.message}")
        println("Original message: $message")
        throwable?.printStackTrace()
    }
}

@Suppress("TooGenericExceptionCaught", "PrintStackTrace")
fun main() {
    // Install global exception handler to prevent app crashes
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        println("=== UNCAUGHT EXCEPTION ON THREAD: ${thread.name} ===")
        println("Exception: ${throwable.javaClass.name}")
        println("Message: ${throwable.message}")
        throwable.printStackTrace()
        println("=== END UNCAUGHT EXCEPTION ===")
        // Do NOT exit - let the app stay open
    }

    // Catch any initialization errors before UI starts
    var startupError: Pair<String, String>? = null

    try {
        log(LogLevel.INFO, "Starting Money Manager application")
    } catch (e: Exception) {
        println("ERROR: Failed to initialize logging: ${e.message}")
        e.printStackTrace()
        startupError = "Failed to initialize logging: ${e.message}" to e.stackTraceToString()
    }

    application {
        // If we had a startup error, show it immediately
        if (startupError != null) {
            Window(
                onCloseRequest = ::exitApplication,
                title = "Money Manager - Startup Error",
                state = rememberWindowState(width = 1000.dp, height = 700.dp),
            ) {
                MinimalErrorScreen(startupError.first, startupError.second)
            }
            return@application
        }

        MainWindow(onExit = ::exitApplication)
    }
}

@Suppress("TooGenericExceptionCaught", "LongMethod", "CyclomaticComplexMethod", "FunctionName")
@Composable
private fun MainWindow(onExit: () -> Unit) {
    // State for managing database selection
    var databasePath by remember { mutableStateOf<Path?>(null) }
    var showDatabaseDialog by remember { mutableStateOf(false) }
    var errorState by remember { mutableStateOf<ErrorState?>(null) }
    var initResult by remember { mutableStateOf<InitResult?>(null) }
    var fatalError by remember { mutableStateOf<Pair<String, String>?>(null) }

    // Initialize on startup
    LaunchedEffect(Unit) {
        try {
            log(LogLevel.INFO, "LaunchedEffect: Starting initialization")
            val defaultDbPath = getDefaultDatabasePath()
            val dbExists = defaultDbPath.toFile().exists()
            log(LogLevel.DEBUG, "Default database path: $defaultDbPath, exists: $dbExists")

            if (dbExists) {
                databasePath = defaultDbPath
                log(LogLevel.INFO, "Existing database found, initializing...")
                // Initialize immediately with the path
                initResult = initializeApplication(defaultDbPath)
                log(LogLevel.INFO, "Initialization result: ${initResult?.javaClass?.simpleName}")
            } else {
                log(LogLevel.INFO, "No existing database, showing dialog")
                showDatabaseDialog = true
            }
        } catch (e: Exception) {
            log(LogLevel.ERROR, "Failed to initialize on startup: ${e.message}", e)
            fatalError = "Failed to initialize: ${e.message}" to e.stackTraceToString()
        }
    }

    // Window title based on state
    val windowTitle =
        when {
            fatalError != null -> "Money Manager - Fatal Error"
            initResult is InitResult.Error -> "Money Manager - Error"
            databasePath != null -> "Money Manager - ${databasePath?.fileName}"
            else -> "Money Manager - Setup"
        }

    Window(
        onCloseRequest = onExit,
        title = windowTitle,
        state = rememberWindowState(width = 1000.dp, height = 700.dp),
    ) {
        // Show fatal error screen if something went very wrong
        val currentFatalError = fatalError
        if (currentFatalError != null) {
            SimpleFallbackErrorScreen(
                message = currentFatalError.first,
                stackTrace = currentFatalError.second,
            )
            return@Window
        }

        // Show database selection dialog if needed
        if (showDatabaseDialog && initResult == null) {
            DatabaseSelectionDialog(
                defaultPath = getDefaultDatabasePath(),
                onDatabaseSelected = { selectedPath ->
                    try {
                        log(LogLevel.INFO, "User selected database path: $selectedPath")
                        databasePath = selectedPath
                        showDatabaseDialog = false
                        log(LogLevel.INFO, "Database path set successfully")
                        // Initialize after path is set (directory creation is handled by DatabaseDriverFactory)
                        initResult = initializeApplication(selectedPath)
                    } catch (e: Exception) {
                        log(LogLevel.ERROR, "Failed to set database path: ${e.message}", e)
                        errorState =
                            ErrorState(
                                message = "Failed to set database path: ${e.message}",
                                canRecover = true,
                                fullException = e.stackTraceToString(),
                            )
                    }
                },
                onCancel = {
                    log(LogLevel.INFO, "User cancelled database selection - exiting application")
                    onExit()
                },
                onShowFileChooser = {
                    val fileDialog = FileDialog(null as Frame?, "Choose Database Location", FileDialog.SAVE)
                    fileDialog.file = "default.db"
                    fileDialog.isVisible = true

                    val selectedFile = fileDialog.file
                    val selectedDir = fileDialog.directory

                    if (selectedFile != null && selectedDir != null) {
                        Paths.get(selectedDir, selectedFile)
                    } else {
                        null
                    }
                },
            )
        }

        // Show error dialog if present (for non-fatal errors only)
        errorState?.let { error ->
            ErrorDialog(
                error = error,
                onDismiss = {
                    errorState = null
                },
            )
        }

        // Show main app or error screen based on initialization result
        when (val result = initResult) {
            is InitResult.Success -> {
                val currentDbPath = databasePath
                MoneyManagerApp(
                    accountRepository = result.accountRepository,
                    categoryRepository = result.categoryRepository,
                    transactionRepository = result.transactionRepository,
                    appVersion = result.appVersion,
                    databasePath = currentDbPath?.toString() ?: "Unknown",
                )
            }
            is InitResult.Error -> {
                // Use minimal error screen to avoid any Material3 issues
                MinimalErrorScreen(
                    message = result.message,
                    stackTrace = result.fullException,
                )
            }
            null -> {
                // Still initializing or waiting for user input
                // Don't show anything if dialog is open
            }
        }
    }
}

private sealed class InitResult {
    data class Success(
        val accountRepository: com.moneymanager.domain.repository.AccountRepository,
        val categoryRepository: com.moneymanager.domain.repository.CategoryRepository,
        val transactionRepository: com.moneymanager.domain.repository.TransactionRepository,
        val appVersion: AppVersion,
    ) : InitResult()

    data class Error(val message: String, val fullException: String) : InitResult()
}

@Suppress("TooGenericExceptionCaught", "PrintStackTrace", "ReturnCount")
private fun initializeApplication(dbPath: Path): InitResult {
    return try {
        log(LogLevel.INFO, "=== Starting Application Initialization ===")
        log(LogLevel.INFO, "Database path: $dbPath")

        // Create AppComponent with database path
        // DI system will handle DatabaseDriverFactory creation and database initialization
        val component: AppComponent =
            try {
                log(LogLevel.INFO, "Creating DI component...")
                val params = AppComponentParams(databasePath = dbPath.toAbsolutePath().toString())
                val result = AppComponent.create(params)
                log(LogLevel.INFO, "DI component created successfully")
                result
            } catch (e: Exception) {
                log(LogLevel.ERROR, "EXCEPTION creating DI component", e)
                e.printStackTrace()
                return InitResult.Error(
                    "Failed to create DI component: ${e.javaClass.simpleName}: ${e.message}",
                    e.stackTraceToString(),
                )
            }

        val accountRepository = component.accountRepository
        val categoryRepository = component.categoryRepository
        val transactionRepository = component.transactionRepository
        val appVersion = component.appVersion
        log(LogLevel.INFO, "App version: ${appVersion.value}")
        log(LogLevel.INFO, "All repositories initialized successfully")
        log(LogLevel.INFO, "=== Initialization Complete ===")

        InitResult.Success(accountRepository, categoryRepository, transactionRepository, appVersion)
    } catch (e: Exception) {
        log(LogLevel.ERROR, "FATAL ERROR initializing application", e)
        e.printStackTrace()
        InitResult.Error(
            e.message ?: "Unknown error",
            e.stackTraceToString(),
        )
    }
}
