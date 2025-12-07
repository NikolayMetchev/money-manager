package com.moneymanager

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.moneymanager.di.AppComponent
import com.moneymanager.di.AppComponentParams
import com.moneymanager.ui.MoneyManagerApp
import org.lighthousegames.logging.logging

private val logger = logging()

fun main() {
    logger.info { "Starting Money Manager application" }

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
        state = rememberWindowState(width = 1000.dp, height = 700.dp),
    ) {
        MoneyManagerApp(
            databaseManager = component.databaseManager,
            appVersion = component.appVersion,
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
