package com.moneymanager

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.dp
import com.moneymanager.database.DatabaseDriverFactory
import com.moneymanager.di.AppComponent
import com.moneymanager.ui.MoneyManagerApp

fun main() = application {
    // Initialize DI component using Metro-generated code
    val component: AppComponent = AppComponent.create(DatabaseDriverFactory())

    // Get repositories from the component
    val accountRepository = component.accountRepository
    val categoryRepository = component.categoryRepository
    val transactionRepository = component.transactionRepository

    Window(
        onCloseRequest = ::exitApplication,
        title = "Money Manager",
        state = rememberWindowState(width = 1000.dp, height = 700.dp)
    ) {
        MoneyManagerApp(
            accountRepository = accountRepository,
            categoryRepository = categoryRepository,
            transactionRepository = transactionRepository
        )
    }
}
