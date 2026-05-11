package com.moneymanager.android

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.moneymanager.di.AppComponent
import com.moneymanager.di.AppComponentParams
import com.moneymanager.di.database.DatabaseComponent
import com.moneymanager.di.database.toApplication
import com.moneymanager.di.initializeVersionReader
import com.moneymanager.ui.AppStartupHost
import com.moneymanager.ui.error.GlobalSchemaErrorState
import com.moneymanager.ui.error.SchemaErrorDetector
import com.moneymanager.ui.toAppServices

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (SchemaErrorDetector.isSchemaError(throwable)) {
                Log.e(TAG, "Schema error detected: ${throwable.message}", throwable)
                GlobalSchemaErrorState.reportError(
                    databaseLocation = "default",
                    error = throwable,
                )
            } else {
                Log.e(TAG, "Uncaught exception on thread ${thread.name}: ${throwable.message}", throwable)
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }

        initializeVersionReader(applicationContext)

        val params = AppComponentParams(context = applicationContext)
        val component: AppComponent = AppComponent.create(params)

        setContent {
            AppStartupHost(
                databaseManager = component.databaseManager,
                appVersion = component.appVersion,
                createAppServices = { database ->
                    DatabaseComponent.create(database).toApplication().toAppServices()
                },
                onInfoLog = { message -> Log.i(TAG, message) },
                onErrorLog = { message, error -> Log.e(TAG, message, error) },
            )
        }
    }
}
