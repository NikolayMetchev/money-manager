package com.moneymanager.strategycatalog

import com.moneymanager.localsettings.LocalSettings

/**
 * Builds the production controller. The local-directory source needs `java.io.File`, which is only
 * visible from a platform source set, hence the expect/actual — the JVM and Android bodies are
 * otherwise identical (see `buildStrategyCatalogController` in `jvmAndroidMain`).
 */
@Suppress("ktlint:standard:function-naming")
expect fun createStrategyCatalogController(localSettings: LocalSettings): StrategyCatalogController
