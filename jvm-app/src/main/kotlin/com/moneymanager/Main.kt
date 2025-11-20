package com.moneymanager

import androidx.compose.runtime.*
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.dp
import com.moneymanager.database.DatabaseDriverFactory
import com.moneymanager.di.AppComponent
import com.moneymanager.ui.MoneyManagerApp
import java.nio.file.Path

fun main() = application {
    // Check if default database exists
    val defaultDbPath = DatabaseConfig.getDefaultDatabasePath()
    val dbExists = DatabaseConfig.databaseFileExists(defaultDbPath)

    // State for managing database selection
    var databasePath by remember { mutableStateOf<Path?>(if (dbExists) defaultDbPath else null) }
    var showDatabaseDialog by remember { mutableStateOf(!dbExists) }

    // Window title based on state
    val windowTitle = databasePath?.let { "Money Manager - ${it.fileName}" } ?: "Money Manager - Setup"

    Window(
        onCloseRequest = ::exitApplication,
        title = windowTitle,
        state = rememberWindowState(width = 1000.dp, height = 700.dp)
    ) {
        // Show database selection dialog if needed
        if (showDatabaseDialog) {
            DatabaseSelectionDialog(
                defaultPath = defaultDbPath,
                onDatabaseSelected = { selectedPath ->
                    // Ensure directory exists
                    DatabaseConfig.ensureDirectoryExists(selectedPath)
                    databasePath = selectedPath
                    showDatabaseDialog = false
                },
                onCancel = {
                    // User cancelled - exit application
                    exitApplication()
                }
            )
        }

        // Show main app if database path is selected
        databasePath?.let { dbPath ->
            // Initialize database driver with the selected path
            val driverFactory = DatabaseDriverFactory()
            val isNewDatabase = !DatabaseConfig.databaseFileExists(dbPath)
            val driver = remember(dbPath) {
                driverFactory.createDriver(
                    databasePath = DatabaseConfig.getJdbcPath(dbPath),
                    isNewDatabase = isNewDatabase
                )
            }

            // Initialize DI component using Metro-generated code
            val component: AppComponent = remember(driver) { AppComponent.create(driver) }

            // Get repositories from the component
            val accountRepository = component.accountRepository
            val categoryRepository = component.categoryRepository
            val transactionRepository = component.transactionRepository

            MoneyManagerApp(
                accountRepository = accountRepository,
                categoryRepository = categoryRepository,
                transactionRepository = transactionRepository,
                databasePath = dbPath.toString()
            )
        }
    }
}
