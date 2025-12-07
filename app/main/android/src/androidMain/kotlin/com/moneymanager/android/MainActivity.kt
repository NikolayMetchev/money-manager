package com.moneymanager.android

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.moneymanager.di.AppComponent
import com.moneymanager.di.AppComponentParams
import com.moneymanager.di.initializeVersionReader
import com.moneymanager.ui.MoneyManagerApp

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize version reader with application context
        initializeVersionReader(applicationContext)

        // Initialize DI component with Android context
        val params = AppComponentParams(context = applicationContext)
        val component: AppComponent = AppComponent.create(params)

        setContent {
            MoneyManagerApp(
                databaseManager = component.databaseManager,
                appVersion = component.appVersion,
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
