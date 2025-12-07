package com.moneymanager

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.moneymanager.database.DatabaseState
import com.moneymanager.database.DbLocation
import com.moneymanager.database.RepositorySet
import com.moneymanager.di.AppComponent
import com.moneymanager.di.AppComponentParams
import com.moneymanager.ui.DatabaseSelectionDialog
import com.moneymanager.ui.MoneyManagerApp
import com.moneymanager.ui.components.DatabaseSchemaErrorDialog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

@Suppress("LongMethod", "FunctionName", "CyclomaticComplexMethod", "TooGenericExceptionCaught")
@Composable
private fun MainWindow(onExit: () -> Unit) {
    val coroutineScope = rememberCoroutineScope()

    // Initialize DI component once
    val component =
        remember {
            logger.info { "Creating DI component..." }
            val params = AppComponentParams()
            AppComponent.create(params).also {
                logger.info { "DI component created successfully" }
            }
        }

    val databaseManager = component.databaseManager
    val appVersion = component.appVersion

    var databaseState by remember { mutableStateOf<DatabaseState>(DatabaseState.NoDatabaseSelected) }
    var showDatabaseDialog by remember { mutableStateOf(false) }
    var schemaErrorInfo by remember { mutableStateOf<Pair<DbLocation, Throwable>?>(null) }

    // Initialize on startup
    LaunchedEffect(Unit) {
        logger.info { "LaunchedEffect: Starting initialization" }
        val defaultLocation = databaseManager.getDefaultLocation()
        val dbExists = databaseManager.databaseExists(defaultLocation)
        logger.debug { "Default database location: $defaultLocation, exists: $dbExists" }

        if (dbExists) {
            logger.info { "Existing database found, opening..." }
            try {
                val database = databaseManager.openDatabase(defaultLocation)
                val repositories = RepositorySet(database)
                // Test that we can actually query the database
                // This will catch schema errors like missing views/tables
                repositories.accountRepository.getAllAccounts().first()
                databaseState = DatabaseState.DatabaseLoaded(defaultLocation, repositories)
                logger.info { "Database opened successfully" }
            } catch (e: Exception) {
                logger.error { "Failed to open database: ${e.message}" }
                // Store error info to show schema error dialog
                schemaErrorInfo = defaultLocation to e
                databaseState = DatabaseState.Error(e)
            }
        } else {
            logger.info { "No existing database, showing dialog" }
            showDatabaseDialog = true
        }
    }

    val windowTitle =
        when (val state = databaseState) {
            is DatabaseState.DatabaseLoaded -> "Money Manager - ${state.location}"
            is DatabaseState.NoDatabaseSelected -> "Money Manager - No Database"
            is DatabaseState.Error -> "Money Manager - Error"
        }

    Window(
        onCloseRequest = onExit,
        title = windowTitle,
        state = rememberWindowState(width = 1000.dp, height = 700.dp),
    ) {
        // Show database schema error dialog if there's an error
        schemaErrorInfo?.let { (location, error) ->
            DatabaseSchemaErrorDialog(
                databaseLocation = location.toString(),
                error = error,
                onBackupAndCreateNew = {
                    coroutineScope.launch {
                        try {
                            logger.info { "Backing up database and creating new one..." }
                            val backupLocation = databaseManager.backupDatabase(location)
                            logger.info { "Database backed up to: $backupLocation" }

                            val database = databaseManager.openDatabase(location)
                            val repositories = RepositorySet(database)
                            databaseState = DatabaseState.DatabaseLoaded(location, repositories)
                            schemaErrorInfo = null
                            logger.info { "New database created successfully" }
                        } catch (e: Exception) {
                            logger.error { "Failed to backup and create new database: ${e.message}" }
                            schemaErrorInfo = location to e
                        }
                    }
                },
                onDeleteAndCreateNew = {
                    coroutineScope.launch {
                        try {
                            logger.info { "Deleting database and creating new one..." }
                            databaseManager.deleteDatabase(location)
                            logger.info { "Database deleted" }

                            val database = databaseManager.openDatabase(location)
                            val repositories = RepositorySet(database)
                            databaseState = DatabaseState.DatabaseLoaded(location, repositories)
                            schemaErrorInfo = null
                            logger.info { "New database created successfully" }
                        } catch (e: Exception) {
                            logger.error { "Failed to delete and create new database: ${e.message}" }
                            schemaErrorInfo = location to e
                        }
                    }
                },
            )
        }

        // Show database selection dialog if needed
        if (showDatabaseDialog && databaseState is DatabaseState.NoDatabaseSelected) {
            DatabaseSelectionDialog(
                defaultPath = databaseManager.getDefaultLocation(),
                onDatabaseSelected = { selectedPath ->
                    logger.info { "User selected database path: $selectedPath" }
                    showDatabaseDialog = false
                    coroutineScope.launch {
                        val location = DbLocation(selectedPath)
                        try {
                            logger.info { "Opening database at: $location" }
                            val database = databaseManager.openDatabase(location)
                            val repositories = RepositorySet(database)
                            databaseState = DatabaseState.DatabaseLoaded(location, repositories)
                            logger.info { "Database initialized successfully" }
                        } catch (e: Exception) {
                            logger.error { "Failed to open database: ${e.message}" }
                            // Store error info to show schema error dialog
                            schemaErrorInfo = location to e
                            databaseState = DatabaseState.Error(e)
                        }
                    }
                },
                onCancel = {
                    logger.info { "User cancelled database selection - staying in no database state" }
                    showDatabaseDialog = false
                    // Keep databaseState as NoDatabaseSelected
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

        // Show main app once database is loaded
        when (val state = databaseState) {
            is DatabaseState.DatabaseLoaded -> {
                MoneyManagerApp(
                    repositorySet = state.repositories,
                    appVersion = appVersion,
                    databaseLocation = state.location,
                )
            }
            is DatabaseState.NoDatabaseSelected -> {
                // Show UI with option to open/create database in future
            }
            is DatabaseState.Error -> {
                // Show error UI in future
                logger.error { "Database error: ${state.error.message}" }
            }
        }
    }
}
