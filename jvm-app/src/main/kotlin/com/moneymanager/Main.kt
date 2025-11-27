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
import com.moneymanager.database.DEFAULT_DATABASE_PATH
import com.moneymanager.database.DbLocation
import com.moneymanager.di.AppComponent
import com.moneymanager.di.AppComponentParams
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.domain.repository.TransactionRepository
import com.moneymanager.ui.DatabaseSelectionDialog
import com.moneymanager.ui.MoneyManagerApp
import org.lighthousegames.logging.logging
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Paths

private val logger = logging()

fun main() {
    logger.info { "Starting Money Manager application" }

    application {
        MainWindow(onExit = ::exitApplication)
    }
}

@Suppress("LongMethod", "FunctionName")
@Composable
private fun MainWindow(onExit: () -> Unit) {
    var databasePath by remember { mutableStateOf<DbLocation?>(null) }
    var showDatabaseDialog by remember { mutableStateOf(false) }
    var appState by remember { mutableStateOf<AppState?>(null) }

    // Initialize on startup
    LaunchedEffect(Unit) {
        logger.info { "LaunchedEffect: Starting initialization" }
        val defaultDbPath = DEFAULT_DATABASE_PATH
        val dbExists = defaultDbPath.exists()
        logger.debug { "Default database path: $defaultDbPath, exists: $dbExists" }

        if (dbExists) {
            databasePath = defaultDbPath
            logger.info { "Existing database found, initializing..." }
            appState = initializeApplication(defaultDbPath)
            logger.info { "Initialization complete" }
        } else {
            logger.info { "No existing database, showing dialog" }
            showDatabaseDialog = true
        }
    }

    val windowTitle =
        when {
            databasePath != null -> "Money Manager - $databasePath"
            else -> "Money Manager - Setup"
        }

    Window(
        onCloseRequest = onExit,
        title = windowTitle,
        state = rememberWindowState(width = 1000.dp, height = 700.dp),
    ) {
        // Show database selection dialog if needed
        if (showDatabaseDialog && appState == null) {
            DatabaseSelectionDialog(
                defaultPath = DEFAULT_DATABASE_PATH,
                onDatabaseSelected = { selectedPath ->
                    logger.info { "User selected database path: $selectedPath" }
                    databasePath = DbLocation(selectedPath)
                    showDatabaseDialog = false
                    logger.info { "Database path set successfully" }
                    appState = initializeApplication(DbLocation(selectedPath))
                },
                onCancel = {
                    logger.info { "User cancelled database selection - exiting application" }
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

        // Show main app once initialized
        appState?.let { state ->
            val currentDbPath = databasePath
            MoneyManagerApp(
                accountRepository = state.accountRepository,
                categoryRepository = state.categoryRepository,
                transactionRepository = state.transactionRepository,
                appVersion = state.appVersion,
                databasePath = currentDbPath?.toString() ?: "Unknown",
            )
        }
    }
}

private data class AppState(
    val accountRepository: AccountRepository,
    val categoryRepository: CategoryRepository,
    val transactionRepository: TransactionRepository,
    val appVersion: AppVersion,
)

private fun initializeApplication(dbLocation: DbLocation): AppState {
    logger.info { "=== Starting Application Initialization ===" }
    logger.info { "Database path: $dbLocation" }

    logger.info { "Creating DI component..." }
    val params = AppComponentParams()
    val component: AppComponent = AppComponent.create(params)
    logger.info { "DI component created successfully" }

    val accountRepository = component.repositoryFactory.createAccountRepository({ DEFAULT_DATABASE_PATH })
    val categoryRepository = component.repositoryFactory.createCategoryRepository({ DEFAULT_DATABASE_PATH })
    val transactionRepository = component.repositoryFactory.createTransactionRepository({ DEFAULT_DATABASE_PATH })
    val appVersion = component.appVersion
    logger.info { "App version: ${appVersion.value}" }
    logger.info { "All repositories initialized successfully" }
    logger.info { "=== Initialization Complete ===" }

    return AppState(accountRepository, categoryRepository, transactionRepository, appVersion)
}
