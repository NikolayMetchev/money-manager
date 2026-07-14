package com.moneymanager.localsettings.di

import com.moneymanager.di.params.AppComponentParams
import com.moneymanager.localsettings.AndroidLocalSettings
import com.moneymanager.localsettings.LocalSettings

@Suppress("ktlint:standard:function-naming")
actual fun createLocalSettings(params: AppComponentParams): LocalSettings = AndroidLocalSettings(params.context)
