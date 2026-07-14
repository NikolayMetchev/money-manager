package com.moneymanager.localsettings.di

import com.moneymanager.di.params.AppComponentParams
import com.moneymanager.localsettings.LocalSettings

/**
 * Creates the platform-specific [LocalSettings] implementation (Java Preferences on JVM,
 * SharedPreferences on Android).
 */
@Suppress("ktlint:standard:function-naming")
expect fun createLocalSettings(params: AppComponentParams): LocalSettings
