package com.moneymanager.strategycatalog

import com.moneymanager.httpclient.platformHttpClient
import com.moneymanager.localsettings.LocalSettings

/** Builds the production controller over the platform HTTP engine. */
fun createStrategyCatalogController(localSettings: LocalSettings): StrategyCatalogController =
    StrategyCatalogController(
        remoteSource = StrategyCatalogClient(platformHttpClient()),
        localDirectorySourceFactory = ::LocalDirectoryStrategyCatalogSource,
        localSettings = localSettings,
    )
