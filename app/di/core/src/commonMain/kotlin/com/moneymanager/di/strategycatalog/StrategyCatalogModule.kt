package com.moneymanager.di.strategycatalog

import com.moneymanager.di.AppScope
import com.moneymanager.strategycatalog.StrategyCatalogController
import com.moneymanager.strategycatalog.createStrategyCatalogController
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/** Provides the central strategy-catalog client (GitHub Pages-hosted strategy library). */
@ContributesTo(AppScope::class)
interface StrategyCatalogModule {
    @Provides
    @SingleIn(AppScope::class)
    fun provideStrategyCatalogController(): StrategyCatalogController = createStrategyCatalogController()
}
