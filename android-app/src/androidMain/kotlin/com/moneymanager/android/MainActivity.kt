package com.moneymanager.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.moneymanager.di.AppComponent
import com.moneymanager.di.initializeVersionReader
import com.moneymanager.domain.di.AppComponentParams
import com.moneymanager.ui.MoneyManagerApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize version reader with application context
        initializeVersionReader(applicationContext)

        // Initialize DI component with Android context
        // Metro DI will handle DatabaseDriverFactory creation and database initialization
        val params = AppComponentParams(context = applicationContext)
        val component: AppComponent = AppComponent.create(params)

        // Get repositories and version from the component
        val accountRepository = component.accountRepository
        val categoryRepository = component.categoryRepository
        val transactionRepository = component.transactionRepository
        val appVersion = component.appVersion

        // Get the actual database path on Android
        val dbPath = applicationContext.getDatabasePath("money_manager.db").absolutePath

        setContent {
            MoneyManagerApp(
                accountRepository = accountRepository,
                categoryRepository = categoryRepository,
                transactionRepository = transactionRepository,
                appVersion = appVersion,
                databasePath = dbPath,
            )
        }
    }
}
