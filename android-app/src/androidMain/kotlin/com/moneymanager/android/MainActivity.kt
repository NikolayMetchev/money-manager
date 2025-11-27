package com.moneymanager.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.moneymanager.database.DefaultLocationMissingListener
import com.moneymanager.di.AppComponent
import com.moneymanager.di.AppComponentParams
import com.moneymanager.di.initializeVersionReader
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

        // Get repository factory and version from the component
        val repositoryFactory = component.repositoryFactory
        val appVersion = component.appVersion

        // Create repositories from factory with default listener
        val listener = DefaultLocationMissingListener {
            com.moneymanager.database.DbLocation(com.moneymanager.database.DEFAULT_DATABASE_NAME)
        }
        val accountRepository = repositoryFactory.createAccountRepository(listener)
        val categoryRepository = repositoryFactory.createCategoryRepository(listener)
        val transactionRepository = repositoryFactory.createTransactionRepository(listener)

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
