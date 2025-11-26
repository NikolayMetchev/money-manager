package com.moneymanager.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.moneymanager.database.DatabaseDriverFactory
import com.moneymanager.di.AppComponent
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.ui.MoneyManagerApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Read app version from assets
        val appVersion = readAppVersion()

        // Initialize database driver with Android context
        val driverFactory = DatabaseDriverFactory(applicationContext)
        val driver = driverFactory.createDriver()

        // Initialize DI component using Metro-generated code
        val component: AppComponent = AppComponent.create(driver, appVersion)

        // Get repositories from the component
        val accountRepository = component.accountRepository
        val categoryRepository = component.categoryRepository
        val transactionRepository = component.transactionRepository

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

    /**
     * Reads the application version from the VERSION file in assets.
     */
    private fun readAppVersion(): AppVersion {
        return try {
            val versionString =
                applicationContext.assets.open("VERSION")
                    .bufferedReader()
                    .use { it.readText().trim() }
            AppVersion(versionString)
        } catch (e: Exception) {
            AppVersion("Unknown")
        }
    }
}
