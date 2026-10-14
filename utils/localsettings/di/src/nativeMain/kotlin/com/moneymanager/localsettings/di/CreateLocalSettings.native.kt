package com.moneymanager.localsettings.di

import com.moneymanager.di.params.AppComponentParams
import com.moneymanager.localsettings.FileLocalSettings
import com.moneymanager.localsettings.LocalSettings
import com.moneymanager.localsettings.defaultLocalSettingsPath

// The same file the desktop app uses, so the CLI sees the desktop's last database and cloud bindings.
@Suppress("ktlint:standard:function-naming", "UnusedParameter")
actual fun createLocalSettings(params: AppComponentParams): LocalSettings = FileLocalSettings(defaultLocalSettingsPath())
