package com.moneymanager.di

import com.moneymanager.database.DatabaseManager
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.localsettings.LocalSettings
import com.moneymanager.remotestorage.RemoteStorageProviderFactory
import com.moneymanager.remotestorage.sync.RemoteDatabaseSyncService
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides

@DependencyGraph(AppScope::class)
interface AppComponent {
    val databaseManager: DatabaseManager
    val appVersion: AppVersion
    val localSettings: LocalSettings
    val remoteDatabaseSyncService: RemoteDatabaseSyncService
    val remoteStorageProviderFactory: RemoteStorageProviderFactory

    @DependencyGraph.Factory
    interface Factory {
        fun create(
            @Provides params: AppComponentParams,
        ): AppComponent
    }
}
