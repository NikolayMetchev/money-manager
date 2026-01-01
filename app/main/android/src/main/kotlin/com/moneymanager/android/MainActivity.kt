package com.moneymanager.android

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.moneymanager.di.AppComponent
import com.moneymanager.di.AppComponentParams
import com.moneymanager.di.database.DatabaseComponent
import com.moneymanager.di.initializeVersionReader
import com.moneymanager.ui.MoneyManagerApp
import com.moneymanager.ui.error.GlobalSchemaErrorState
import com.moneymanager.ui.error.SchemaErrorDetector

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
            MoneyManagerApp(
                databaseManager = component.databaseManager,
                appVersion = component.appVersion,
                createRepositories = { database, callback ->
                    val databaseComponent = DatabaseComponent.create(database)
                    callback.onRepositoriesReady(
                        accountRepository = databaseComponent.accountRepository,
                        attributeTypeRepository = databaseComponent.attributeTypeRepository,
                        auditRepository = databaseComponent.auditRepository,
                        categoryRepository = databaseComponent.categoryRepository,
                        csvImportRepository = databaseComponent.csvImportRepository,
                        csvImportStrategyRepository = databaseComponent.csvImportStrategyRepository,
                        currencyRepository = databaseComponent.currencyRepository,
                        deviceRepository = databaseComponent.deviceRepository,
                        maintenanceService = databaseComponent.maintenanceService,
                        transactionRepository = databaseComponent.transactionRepository,
                        transferAttributeRepository = databaseComponent.transferAttributeRepository,
                        transferSourceRepository = databaseComponent.transferSourceRepository,
                        transferSourceQueries = databaseComponent.transferSourceQueries,
                        deviceId = databaseComponent.deviceId,
                    )
                },
                onLog = { message, error ->
                    if (error != null) {
                        Log.e(TAG, message, error)
                        Log.e(TAG, "Stack trace: ${error.stackTraceToString()}")
                    } else {
                        Log.i(TAG, message)
                    }
                },
            )
        }
    }
}
