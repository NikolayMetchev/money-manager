package com.moneymanager.di

import com.moneymanager.database.DatabaseManager
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.localsettings.LocalSettings
import com.moneymanager.remotestorage.sync.RemoteDatabaseController
import com.moneymanager.remotestorage.sync.StrategySyncController
import com.moneymanager.strategycatalog.StrategyCatalogController
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides

@DependencyGraph(AppScope::class)
interface AppComponent {
    val databaseManager: DatabaseManager
    val appVersion: AppVersion
    val localSettings: LocalSettings
    val remoteDatabaseController: RemoteDatabaseController
    val strategySyncController: StrategySyncController
    val strategyCatalogController: StrategyCatalogController

    @DependencyGraph.Factory
    interface Factory {
        fun create(
            @Provides params: AppComponentParams,
        ): AppComponent
    }
}
