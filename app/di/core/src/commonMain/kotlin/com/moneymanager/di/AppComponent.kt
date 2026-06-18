package com.moneymanager.di

import com.moneymanager.database.DatabaseManager
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.localsettings.LocalSettings
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides

@DependencyGraph(AppScope::class)
interface AppComponent {
    val databaseManager: DatabaseManager
    val appVersion: AppVersion
    val localSettings: LocalSettings

    @DependencyGraph.Factory
    interface Factory {
        fun create(
            @Provides params: AppComponentParams,
        ): AppComponent
    }
}
