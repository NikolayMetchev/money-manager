package com.moneymanager

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.moneymanager.di.AppComponent
import com.moneymanager.di.AppComponentParams
import com.moneymanager.di.database.DatabaseComponent
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.ui.MoneyManagerApp
import com.moneymanager.ui.components.DatabaseSchemaErrorDialog
import com.moneymanager.ui.error.GlobalSchemaErrorState
import com.moneymanager.ui.error.SchemaErrorDetector
import kotlinx.coroutines.launch
import org.lighthousegames.logging.logging

private val logger = logging()

fun main() {
    logger.info { "Starting Money Manager application" }

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

private sealed class AppDatabaseState {
    data object Loading : AppDatabaseState()

    data class Loaded(
        val location: DbLocation,
        val databaseComponent: DatabaseComponent,
    ) : AppDatabaseState()

    data class Error(val location: DbLocation, val error: Throwable) : AppDatabaseState()
}

@Composable
@Suppress("FunctionName")
private fun MainWindow(onExit: () -> Unit) {
    // Initialize DI component once
    val component =
        AppComponent.create(AppComponentParams()).also {
            logger.info { "DI component created successfully" }
        }

    val databaseManager = component.databaseManager
    val appVersion = component.appVersion
    val scope = rememberCoroutineScope()
    var databaseState by remember { mutableStateOf<AppDatabaseState>(AppDatabaseState.Loading) }

    // Open database on first composition
    LaunchedEffect(Unit) {
        val location = databaseManager.getDefaultLocation()
        try {
            logger.info { "Opening database at: $location" }
            val database = databaseManager.openDatabase(location)
            val databaseComponent = DatabaseComponent.create(database)
            // Force initialization of device ID to detect schema errors early
            databaseComponent.deviceId
            databaseState = AppDatabaseState.Loaded(location, databaseComponent)
            logger.info { "Database opened successfully" }
        } catch (expected: Exception) {
            logger.error(expected) { "Failed to open database: ${expected.message}" }
            databaseState = AppDatabaseState.Error(location, expected)
        }
    }

    // Observe global schema error state from Flow collection error handlers
    val globalSchemaError by GlobalSchemaErrorState.schemaError.collectAsState()

    // Determine which error to show - prioritize global errors (runtime) over local (startup)
    val effectiveSchemaError: Pair<DbLocation, Throwable>? =
        globalSchemaError?.let { info ->
            val location =
                (databaseState as? AppDatabaseState.Loaded)?.location
                    ?: databaseManager.getDefaultLocation()
            location to info.error
        } ?: (databaseState as? AppDatabaseState.Error)?.let { it.location to it.error }

    Window(
        onCloseRequest = onExit,
        title = "Money Manager",
        state = rememberWindowState(width = 1000.dp, height = 900.dp),
    ) {
        when (val state = databaseState) {
            is AppDatabaseState.Loaded -> {
                val dc = state.databaseComponent
                MoneyManagerApp(
                    appVersion = appVersion,
                    databaseLocation = state.location,
                    accountRepository = dc.accountRepository,
                    attributeTypeRepository = dc.attributeTypeRepository,
                    auditRepository = dc.auditRepository,
                    categoryRepository = dc.categoryRepository,
                    csvImportRepository = dc.csvImportRepository,
                    csvImportStrategyRepository = dc.csvImportStrategyRepository,
                    currencyRepository = dc.currencyRepository,
                    deviceRepository = dc.deviceRepository,
                    maintenanceService = dc.maintenanceService,
                    transactionRepository = dc.transactionRepository,
                    transferAttributeRepository = dc.transferAttributeRepository,
                    transferSourceRepository = dc.transferSourceRepository,
                    transferSourceQueries = dc.transferSourceQueries,
                    deviceId = dc.deviceId,
                )
            }
            is AppDatabaseState.Loading -> {
                // Loading...
            }
            is AppDatabaseState.Error -> {
                // Error dialog is shown below
            }
        }

        // Show database schema error dialog if there's an error
        effectiveSchemaError?.let { (location, error) ->
            DatabaseSchemaErrorDialog(
                databaseLocation = location.toString(),
                error = error,
                onBackupAndCreateNew = {
                    scope.launch {
                        try {
                            logger.info { "Backing up database and creating new one..." }
                            val backupLocation = databaseManager.backupDatabase(location)
                            logger.info { "Database backed up to: $backupLocation" }

                            val database = databaseManager.openDatabase(location)
                            val databaseComponent = DatabaseComponent.create(database)
                            databaseState = AppDatabaseState.Loaded(location, databaseComponent)
                            GlobalSchemaErrorState.clearError()
                            logger.info { "New database created successfully" }
                        } catch (expected: Exception) {
                            logger.error(expected) { "Failed to backup and create new database" }
                            databaseState = AppDatabaseState.Error(location, expected)
                        }
                    }
                },
                onDeleteAndCreateNew = {
                    scope.launch {
                        try {
                            logger.info { "Deleting database and creating new one..." }
                            databaseManager.deleteDatabase(location)
                            logger.info { "Database deleted" }

                            val database = databaseManager.openDatabase(location)
                            val databaseComponent = DatabaseComponent.create(database)
                            databaseState = AppDatabaseState.Loaded(location, databaseComponent)
                            GlobalSchemaErrorState.clearError()
                            logger.info { "New database created successfully" }
                        } catch (expected: Exception) {
                            logger.error(expected) { "Failed to delete and create new database" }
                            databaseState = AppDatabaseState.Error(location, expected)
                        }
                    }
                },
            )
        }
    }
}
