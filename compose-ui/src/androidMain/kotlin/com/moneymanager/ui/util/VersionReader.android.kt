package com.moneymanager.ui.util

import android.content.res.AssetManager

/**
 * Android implementation of version reader.
 * Reads the VERSION file from the assets folder.
 */
private var assetManager: AssetManager? = null

/**
 * Initialize the asset manager for version reading.
 * Should be called from the Application or Activity.
 */
fun initVersionReader(assets: AssetManager) {
    assetManager = assets
}

actual fun readAppVersion(): String {
    return try {
        assetManager?.open("VERSION")?.bufferedReader()?.use { it.readText().trim() } ?: "Unknown"
    } catch (e: Exception) {
        "Unknown"
    }
}
