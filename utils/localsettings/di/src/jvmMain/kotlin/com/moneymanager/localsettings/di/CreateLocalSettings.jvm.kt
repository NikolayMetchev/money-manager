package com.moneymanager.localsettings.di

import com.moneymanager.di.params.AppComponentParams
import com.moneymanager.localsettings.LocalSettings
import com.moneymanager.localsettings.desktopLocalSettings

@Suppress("ktlint:standard:function-naming")
actual fun createLocalSettings(params: AppComponentParams): LocalSettings = desktopLocalSettings
