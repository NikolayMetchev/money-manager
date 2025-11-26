package com.moneymanager.ui.util

/**
 * JVM implementation of version reader.
 * Reads the VERSION file from the classpath resources.
 */
actual fun readAppVersion(): String {
    return try {
        // Read VERSION file from resources
        val versionStream = object {}.javaClass.getResourceAsStream("/VERSION")
        versionStream?.bufferedReader()?.use { it.readText().trim() } ?: "Unknown"
    } catch (e: Exception) {
        "Unknown"
    }
}
