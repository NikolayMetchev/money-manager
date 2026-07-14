package com.moneymanager.localsettings.di

import com.moneymanager.di.params.AppComponentParams
import com.moneymanager.di.scope.AppScope
import com.moneymanager.localsettings.LocalSettings
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * DI module that provides the app-level [LocalSettings] store. Contributes to AppScope only,
 * since it must persist across database switches.
 */
@ContributesTo(AppScope::class)
interface LocalSettingsModule {
    @Provides
    @SingleIn(AppScope::class)
    fun provideLocalSettings(params: AppComponentParams): LocalSettings = createLocalSettings(params)
}
