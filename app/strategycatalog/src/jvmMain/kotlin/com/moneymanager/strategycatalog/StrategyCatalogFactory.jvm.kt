package com.moneymanager.strategycatalog

import com.moneymanager.localsettings.LocalSettings

@Suppress("ktlint:standard:function-naming")
actual fun createStrategyCatalogController(localSettings: LocalSettings): StrategyCatalogController =
    buildStrategyCatalogController(localSettings)
