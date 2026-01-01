package com.moneymanager.ui.test

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.moneymanager.database.DatabaseManager
import com.moneymanager.di.database.DatabaseComponent
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.ui.MoneyManagerApp
import com.moneymanager.ui.components.DatabaseSchemaErrorDialog
import com.moneymanager.ui.error.GlobalSchemaErrorState
import kotlinx.coroutines.launch

private sealed class TestDatabaseState {
    data object Loading : TestDatabaseState()

    data class Loaded(
        val location: DbLocation,
        val databaseComponent: DatabaseComponent,
    ) : TestDatabaseState()

    data class Error(val location: DbLocation, val error: Throwable) : TestDatabaseState()
}

/**
 * Test wrapper composable that handles database opening and calls MoneyManagerApp.
 * This provides the same interface as the old MoneyManagerApp that handled database loading.
 */
@Composable
fun TestMoneyManagerApp(
    databaseManager: DatabaseManager,
    appVersion: AppVersion,
) {
    val scope = rememberCoroutineScope()
    var databaseState by remember { mutableStateOf<TestDatabaseState>(TestDatabaseState.Loading) }

    LaunchedEffect(Unit) {
        val location = databaseManager.getDefaultLocation()
        try {
            val database = databaseManager.openDatabase(location)
            val component = DatabaseComponent.create(database)
            // Force initialization of all lazy properties to detect schema errors early
            component.deviceId
            databaseState = TestDatabaseState.Loaded(location, component)
        } catch (expected: Exception) {
            databaseState = TestDatabaseState.Error(location, expected)
        }
    }

    // Observe global schema error state from Flow collection error handlers
    val globalSchemaError by GlobalSchemaErrorState.schemaError.collectAsState()

    // Determine which error to show - prioritize global errors (runtime) over local (startup)
    val effectiveSchemaError: Pair<DbLocation, Throwable>? =
        globalSchemaError?.let { info ->
            val location =
                (databaseState as? TestDatabaseState.Loaded)?.location
                    ?: databaseManager.getDefaultLocation()
            location to info.error
        } ?: (databaseState as? TestDatabaseState.Error)?.let { it.location to it.error }

    when (val state = databaseState) {
        is TestDatabaseState.Loaded -> {
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
        is TestDatabaseState.Loading -> {
            // Loading...
        }
        is TestDatabaseState.Error -> {
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
                        databaseManager.backupDatabase(location)
                        val database = databaseManager.openDatabase(location)
                        val component = DatabaseComponent.create(database)
                        databaseState = TestDatabaseState.Loaded(location, component)
                        GlobalSchemaErrorState.clearError()
                    } catch (expected: Exception) {
                        databaseState = TestDatabaseState.Error(location, expected)
                    }
                }
            },
            onDeleteAndCreateNew = {
                scope.launch {
                    try {
                        databaseManager.deleteDatabase(location)
                        val database = databaseManager.openDatabase(location)
                        val component = DatabaseComponent.create(database)
                        databaseState = TestDatabaseState.Loaded(location, component)
                        GlobalSchemaErrorState.clearError()
                    } catch (expected: Exception) {
                        databaseState = TestDatabaseState.Error(location, expected)
                    }
                }
            },
        )
    }
}
