package com.moneymanager.di

import com.moneymanager.localsettings.JvmLocalSettings
import com.moneymanager.localsettings.LocalSettings

@Suppress("ktlint:standard:function-naming")
actual fun createLocalSettings(params: AppComponentParams): LocalSettings = JvmLocalSettings()
