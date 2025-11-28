package com.moneymanager.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.moneymanager.database.DatabaseState
import com.moneymanager.di.AppComponent
import com.moneymanager.di.AppComponentParams
import com.moneymanager.di.initializeVersionReader
import com.moneymanager.ui.MoneyManagerApp
import kotlinx.coroutines.Dispatchers
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
        val repositoryFactory = component.repositoryFactory
        val appVersion = component.appVersion

        setContent {
            var databaseState by remember { mutableStateOf<DatabaseState>(DatabaseState.NoDatabaseSelected) }

            // Open default database on startup
            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) {
                    try {
                        val defaultLocation = databaseManager.getDefaultLocation()
                        val database = databaseManager.openDatabase(defaultLocation)
                        val repositories = repositoryFactory.createRepositories(database)
                        databaseState = DatabaseState.DatabaseLoaded(defaultLocation, repositories)
                    } catch (e: Exception) {
                        databaseState = DatabaseState.Error(e)
                    }
                }
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
                    // TODO: Show error UI
                }
            }
        }
    }
}
