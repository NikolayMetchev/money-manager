package com.moneymanager.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.moneymanager.database.DatabaseInitializationProgress
import com.moneymanager.database.DatabaseManager
import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.ui.components.DatabaseSchemaErrorDialog
import com.moneymanager.ui.components.DatabaseStartupProgressScreen
import com.moneymanager.ui.error.GlobalSchemaErrorState
import com.moneymanager.ui.error.SchemaErrorDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private sealed class AppDatabaseState {
    data class Loading(
        val progress: DatabaseInitializationProgress = initialDatabaseProgress(),
    ) : AppDatabaseState()

    data class Loaded(
        val location: DbLocation,
        val services: AppServices,
    ) : AppDatabaseState()

    data class Error(
        val location: DbLocation,
        val error: Throwable,
    ) : AppDatabaseState()
}

private fun initialDatabaseProgress() =
    DatabaseInitializationProgress(
        text = "Finding the default database...",
        completedSteps = 0,
        totalSteps = 1,
    )

@Composable
fun AppStartupHost(
    databaseManager: DatabaseManager,
    appVersion: AppVersion,
    createAppServices: (MoneyManagerDatabaseWrapper) -> AppServices,
    onInfoLog: (String) -> Unit,
    onErrorLog: (String, Throwable) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var databaseState by remember { mutableStateOf<AppDatabaseState>(AppDatabaseState.Loading()) }

    LaunchedEffect(Unit) {
        val location = databaseManager.getDefaultLocation()
        try {
            onInfoLog("Opening database at: $location")
            val database =
                databaseManager.openDatabaseWithProgress(location) { progress ->
                    databaseState = AppDatabaseState.Loading(progress)
                }
            databaseState = AppDatabaseState.Loading(DatabaseInitializationProgress("Preparing application services...", 1, 1))
            val services = createAppServices(database)
            databaseState = AppDatabaseState.Loading(DatabaseInitializationProgress("Verifying this device...", 1, 1))
            services.deviceId
            databaseState = AppDatabaseState.Loaded(location, services)
            onInfoLog("Database opened successfully")
        } catch (expected: CancellationException) {
            throw expected
        } catch (expected: Exception) {
            onErrorLog("Failed to open database: ${expected.message}", expected)
            databaseState = AppDatabaseState.Error(location, expected)
        }
    }

    val globalSchemaError by GlobalSchemaErrorState.schemaError.collectAsState()
    val effectiveSchemaError: Pair<DbLocation, Throwable>? =
        globalSchemaError
            ?.takeIf { info -> SchemaErrorDetector.isSchemaError(info.error) }
            ?.let { info ->
                val location =
                    (databaseState as? AppDatabaseState.Loaded)?.location
                        ?: databaseManager.getDefaultLocation()
                location to info.error
            } ?: (databaseState as? AppDatabaseState.Error)
            ?.takeIf { SchemaErrorDetector.isSchemaError(it.error) }
            ?.let { it.location to it.error }

    when (val state = databaseState) {
        is AppDatabaseState.Loaded -> {
            MoneyManagerApp(
                appVersion = appVersion,
                databaseLocation = state.location,
                services = state.services,
            )
        }
        is AppDatabaseState.Loading -> DatabaseStartupProgressScreen(state.progress)
        is AppDatabaseState.Error -> Unit
    }

    effectiveSchemaError?.let { (location, error) ->
        DatabaseSchemaErrorDialog(
            databaseLocation = location.toString(),
            error = error,
            onBackupAndCreateNew = {
                scope.launch {
                    recreateDatabase(
                        location = location,
                        databaseManager = databaseManager,
                        createAppServices = createAppServices,
                        databaseStateUpdater = { databaseState = it },
                        onInfoLog = onInfoLog,
                        onErrorLog = onErrorLog,
                        shouldBackup = true,
                    )
                }
            },
            onDeleteAndCreateNew = {
                scope.launch {
                    recreateDatabase(
                        location = location,
                        databaseManager = databaseManager,
                        createAppServices = createAppServices,
                        databaseStateUpdater = { databaseState = it },
                        onInfoLog = onInfoLog,
                        onErrorLog = onErrorLog,
                        shouldBackup = false,
                    )
                }
            },
        )
    }
}

private suspend fun recreateDatabase(
    location: DbLocation,
    databaseManager: DatabaseManager,
    createAppServices: (MoneyManagerDatabaseWrapper) -> AppServices,
    databaseStateUpdater: (AppDatabaseState) -> Unit,
    onInfoLog: (String) -> Unit,
    onErrorLog: (String, Throwable) -> Unit,
    shouldBackup: Boolean,
) {
    try {
        if (shouldBackup) {
            onInfoLog("Backing up database and creating new one...")
            val backupLocation = databaseManager.backupDatabase(location)
            onInfoLog("Database backed up to: $backupLocation")
        } else {
            onInfoLog("Deleting database and creating new one...")
            databaseManager.deleteDatabase(location)
            onInfoLog("Database deleted")
        }

        val database = databaseManager.openDatabase(location)
        val services = createAppServices(database)
        databaseStateUpdater(AppDatabaseState.Loaded(location, services))
        GlobalSchemaErrorState.clearError()
        onInfoLog("New database created successfully")
    } catch (expected: CancellationException) {
        throw expected
    } catch (expected: Exception) {
        val message =
            if (shouldBackup) {
                "Failed to backup and create new database"
            } else {
                "Failed to delete and create new database"
            }
        onErrorLog(message, expected)
        databaseStateUpdater(AppDatabaseState.Error(location, expected))
    }
}
