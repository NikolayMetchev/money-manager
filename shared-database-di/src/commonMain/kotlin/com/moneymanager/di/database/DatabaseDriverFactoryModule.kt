package com.moneymanager.di.database

import com.moneymanager.domain.di.AppScope
import dev.zacsweers.metro.ContributesTo

/**
 * Module that provides DatabaseDriverFactory.
 *
 * Platform-specific implementations handle the creation of DatabaseDriverFactory
 * with the appropriate dependencies (e.g., Context on Android).
 */
@ContributesTo(AppScope::class)
expect interface DatabaseDriverFactoryModule
