package com.moneymanager.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.moneymanager.database.DatabaseState
import com.moneymanager.database.DbLocation
import com.moneymanager.database.RepositorySet
import com.moneymanager.di.AppComponent
import com.moneymanager.di.AppComponentParams
import com.moneymanager.di.initializeVersionReader
import com.moneymanager.ui.MoneyManagerApp
import com.moneymanager.ui.components.DatabaseSchemaErrorDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize version reader with application context
        initializeVersionReader(applicationContext)

        // Initialize DI component with Android context
        val params = AppComponentParams(context = applicationContext)
        val component: AppComponent = AppComponent.create(params)

        val databaseManager = component.databaseManager
        val appVersion = component.appVersion

        setContent {
            val coroutineScope = rememberCoroutineScope()
            var databaseState by remember { mutableStateOf<DatabaseState>(DatabaseState.NoDatabaseSelected) }
            var schemaErrorInfo by remember { mutableStateOf<Pair<DbLocation, Throwable>?>(null) }

            // Open default database on startup
            LaunchedEffect(Unit) {
                val defaultLocation = databaseManager.getDefaultLocation()
                try {
                    val repositories =
                        withContext(Dispatchers.IO) {
                            val database = databaseManager.openDatabase(defaultLocation)
                            val repos = RepositorySet(database)
                            // Test that we can actually query the database
                            // This will catch schema errors like missing views/tables
                            // Try to get all accounts - this uses AccountBalanceView
                            repos.accountRepository.getAllAccounts().first()
                            repos
                        }
                    databaseState = DatabaseState.DatabaseLoaded(defaultLocation, repositories)
                } catch (e: Exception) {
                    // Store error info to show schema error dialog
                    // This catches both database opening errors and schema errors
                    schemaErrorInfo = defaultLocation to e
                    databaseState = DatabaseState.Error(e)
                }
            }

            // Show database schema error dialog if there's an error
            schemaErrorInfo?.let { (location, error) ->
                DatabaseSchemaErrorDialog(
                    databaseLocation = location.toString(),
                    error = error,
                    onBackupAndCreateNew = {
                        coroutineScope.launch(Dispatchers.IO) {
                            try {
                                val backupLocation = databaseManager.backupDatabase(location)
                                val database = databaseManager.openDatabase(location)
                                val repositories = RepositorySet(database)
                                withContext(Dispatchers.Main) {
                                    databaseState = DatabaseState.DatabaseLoaded(location, repositories)
                                    schemaErrorInfo = null
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    schemaErrorInfo = location to e
                                }
                            }
                        }
                    },
                    onDeleteAndCreateNew = {
                        coroutineScope.launch(Dispatchers.IO) {
                            try {
                                databaseManager.deleteDatabase(location)
                                val database = databaseManager.openDatabase(location)
                                val repositories = RepositorySet(database)
                                withContext(Dispatchers.Main) {
                                    databaseState = DatabaseState.DatabaseLoaded(location, repositories)
                                    schemaErrorInfo = null
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    schemaErrorInfo = location to e
                                }
                            }
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
                    // Loading...
                }
                is DatabaseState.Error -> {
                    // Error dialog is shown above
                }
            }
        }
    }
}
