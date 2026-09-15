package com.moneymanager.strategycatalog.di

import com.moneymanager.di.scope.AppScope
import com.moneymanager.localsettings.LocalSettings
import com.moneymanager.strategycatalog.StrategyCatalogController
import com.moneymanager.strategycatalog.createStrategyCatalogController
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/** Provides the central strategy-catalog client (GitHub Pages-hosted strategy library). */
@ContributesTo(AppScope::class)
@BindingContainer
object StrategyCatalogModule {
    @Provides
    @SingleIn(AppScope::class)
    fun provideStrategyCatalogController(localSettings: LocalSettings): StrategyCatalogController =
        createStrategyCatalogController(localSettings)
}
