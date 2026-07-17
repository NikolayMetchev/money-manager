package com.moneymanager.strategycatalog

import com.moneymanager.localsettings.LocalSettings
import io.ktor.client.HttpClient

/**
 * Real implementation shared by the JVM and Android `createStrategyCatalogController` actuals: the
 * engine-less [HttpClient] resolves a platform engine from the runtime classpath (CIO on both JVM and
 * Android via this module's runtimeOnly dependency), and [LocalDirectoryStrategyCatalogSource] is plain
 * `java.io.File`, available identically on both.
 */
internal fun buildStrategyCatalogController(localSettings: LocalSettings): StrategyCatalogController =
    StrategyCatalogController(
        remoteSource = StrategyCatalogClient(HttpClient()),
        localDirectorySourceFactory = { dir -> LocalDirectoryStrategyCatalogSource(dir) },
        localSettings = localSettings,
    )
