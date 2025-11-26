package com.moneymanager.di

import android.content.Context
import com.moneymanager.domain.model.AppVersion

/**
 * Android context for reading the VERSION file from assets.
 * Must be initialized before creating the AppComponent.
 */
private lateinit var androidContext: Context

/**
 * Initialize the Android context for version reading.
 * Should be called from MainActivity.onCreate before creating AppComponent.
 */
fun initializeVersionReader(context: Context) {
    androidContext = context
}

/**
 * Android implementation of version reader.
 * Reads the VERSION file from the assets folder.
 */
@Suppress("TooGenericExceptionCaught")
actual fun readAppVersion(): AppVersion {
    return try {
        val versionString =
            androidContext.assets.open("VERSION")
                .bufferedReader()
                .use { it.readText().trim() }
        AppVersion(versionString)
    } catch (e: Exception) {
        AppVersion("Unknown")
    }
}
