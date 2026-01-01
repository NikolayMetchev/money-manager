package com.moneymanager.android

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.moneymanager.database.DatabaseManager
import com.moneymanager.di.AppComponent
import com.moneymanager.di.AppComponentParams
import com.moneymanager.di.database.DatabaseComponent
import com.moneymanager.di.initializeVersionReader
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.ui.MoneyManagerApp
import com.moneymanager.ui.components.DatabaseSchemaErrorDialog
import com.moneymanager.ui.error.GlobalSchemaErrorState
import com.moneymanager.ui.error.SchemaErrorDetector
import kotlinx.coroutines.launch

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set up global exception handler for schema errors
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (SchemaErrorDetector.isSchemaError(throwable)) {
                Log.e(TAG, "Schema error detected: ${throwable.message}", throwable)
                GlobalSchemaErrorState.reportError(
                    databaseLocation = "default",
                    error = throwable,
                )
            } else {
                // Delegate to default handler for non-schema errors
                Log.e(TAG, "Uncaught exception on thread ${thread.name}: ${throwable.message}", throwable)
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }

        // Initialize version reader with application context
        initializeVersionReader(applicationContext)

        // Initialize DI component with Android context
        val params = AppComponentParams(context = applicationContext)
        val component: AppComponent = AppComponent.create(params)

        setContent {
            MainContent(
                databaseManager = component.databaseManager,
                appVersion = component.appVersion,
            )
        }
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

@Suppress("FunctionName")
@androidx.compose.runtime.Composable
private fun MainContent(
    databaseManager: DatabaseManager,
    appVersion: AppVersion,
) {
    val scope = rememberCoroutineScope()
    var databaseState by remember { mutableStateOf<AppDatabaseState>(AppDatabaseState.Loading) }

    // Open database on first composition
    LaunchedEffect(Unit) {
        val location = databaseManager.getDefaultLocation()
        try {
            Log.i(TAG, "Opening database at: $location")
            val database = databaseManager.openDatabase(location)
            val databaseComponent = DatabaseComponent.create(database)
            // Force initialization of device ID to detect schema errors early
            databaseComponent.deviceId
            databaseState = AppDatabaseState.Loaded(location, databaseComponent)
            Log.i(TAG, "Database opened successfully")
        } catch (expected: Exception) {
            Log.e(TAG, "Failed to open database: ${expected.message}", expected)
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
                        Log.i(TAG, "Backing up database and creating new one...")
                        val backupLocation = databaseManager.backupDatabase(location)
                        Log.i(TAG, "Database backed up to: $backupLocation")

                        val database = databaseManager.openDatabase(location)
                        val databaseComponent = DatabaseComponent.create(database)
                        databaseState = AppDatabaseState.Loaded(location, databaseComponent)
                        GlobalSchemaErrorState.clearError()
                        Log.i(TAG, "New database created successfully")
                    } catch (expected: Exception) {
                        Log.e(TAG, "Failed to backup and create new database", expected)
                        databaseState = AppDatabaseState.Error(location, expected)
                    }
                }
            },
            onDeleteAndCreateNew = {
                scope.launch {
                    try {
                        Log.i(TAG, "Deleting database and creating new one...")
                        databaseManager.deleteDatabase(location)
                        Log.i(TAG, "Database deleted")

                        val database = databaseManager.openDatabase(location)
                        val databaseComponent = DatabaseComponent.create(database)
                        databaseState = AppDatabaseState.Loaded(location, databaseComponent)
                        GlobalSchemaErrorState.clearError()
                        Log.i(TAG, "New database created successfully")
                    } catch (expected: Exception) {
                        Log.e(TAG, "Failed to delete and create new database", expected)
                        databaseState = AppDatabaseState.Error(location, expected)
                    }
                }
            },
        )
    }
}
