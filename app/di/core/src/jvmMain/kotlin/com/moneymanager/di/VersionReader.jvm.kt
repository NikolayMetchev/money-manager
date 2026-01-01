package com.moneymanager.di

import com.moneymanager.domain.model.AppVersion

/**
 * JVM implementation of version reader.
 * Reads the VERSION file from the classpath resources.
 */
actual fun readAppVersion(): AppVersion {
    return try {
        val versionStream = object {}.javaClass.getResourceAsStream("/VERSION")
        val versionString = versionStream?.bufferedReader()?.use { it.readText().trim() } ?: "Unknown"
        AppVersion(versionString)
    } catch (_: Exception) {
        AppVersion("Unknown")
    }
}
