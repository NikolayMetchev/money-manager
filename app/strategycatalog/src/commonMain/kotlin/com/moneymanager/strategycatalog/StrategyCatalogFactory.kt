package com.moneymanager.strategycatalog

import com.moneymanager.localsettings.LocalSettings
import io.ktor.client.HttpClient

/**
 * Builds the production controller. The engine-less [HttpClient] resolves a platform engine from the
 * runtime classpath (CIO on JVM and Android via this module's runtimeOnly dependency).
 */
fun createStrategyCatalogController(localSettings: LocalSettings): StrategyCatalogController =
    StrategyCatalogController(
        remoteSource = StrategyCatalogClient(HttpClient()),
        localDirectorySourceFactory = ::LocalDirectoryStrategyCatalogSource,
        localSettings = localSettings,
    )
