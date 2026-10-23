package com.moneymanager.localsettings.di

import com.moneymanager.di.params.AppComponentParams
import com.moneymanager.localsettings.JsonLocalSettings
import com.moneymanager.localsettings.LocalSettings
import com.moneymanager.localsettings.LocalStorageText

@Suppress("ktlint:standard:function-naming", "UnusedParameter")
actual fun createLocalSettings(params: AppComponentParams): LocalSettings = JsonLocalSettings(LocalStorageText())
