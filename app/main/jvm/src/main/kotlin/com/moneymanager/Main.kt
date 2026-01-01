package com.moneymanager

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.moneymanager.di.AppComponent
import com.moneymanager.di.AppComponentParams
import com.moneymanager.di.database.DatabaseComponent
import com.moneymanager.ui.MoneyManagerApp
import com.moneymanager.ui.error.GlobalSchemaErrorState
import com.moneymanager.ui.error.SchemaErrorDetector
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

@Composable
@Suppress("FunctionName")
private fun MainWindow(onExit: () -> Unit) {
    // Initialize DI component once
    val component =
        AppComponent.create(AppComponentParams()).also {
            logger.info { "DI component created successfully" }
        }

    Window(
        onCloseRequest = onExit,
        title = "Money Manager",
        state = rememberWindowState(width = 1000.dp, height = 900.dp),
    ) {
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
                    logger.error(error) { message }
                    logger.error { "Stack trace: ${error.stackTraceToString()}" }
                } else {
                    logger.info { message }
                }
            },
        )
    }
}
