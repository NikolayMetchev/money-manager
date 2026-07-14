package com.moneymanager.remotestorage.di

import com.moneymanager.database.DatabaseManager
import com.moneymanager.di.params.AppComponentParams
import com.moneymanager.di.scope.AppScope
import com.moneymanager.localsettings.LocalSettings
import com.moneymanager.remotestorage.RemoteStorageProviderFactory
import com.moneymanager.remotestorage.sync.RemoteDatabaseController
import com.moneymanager.remotestorage.sync.RemoteDatabaseSyncService
import com.moneymanager.remotestorage.sync.StrategyRemoteConnectionStore
import com.moneymanager.remotestorage.sync.StrategySyncController
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/** Provides remote-storage (e.g. Google Drive) dependencies. */
@ContributesTo(AppScope::class)
interface RemoteStorageModule {
    @Provides
    @SingleIn(AppScope::class)
    fun provideRemoteStorageProviderFactory(
        params: AppComponentParams,
        localSettings: LocalSettings,
    ): RemoteStorageProviderFactory = createRemoteStorageProviderFactory(params, localSettings)

    @Provides
    @SingleIn(AppScope::class)
    fun provideRemoteDatabaseSyncService(
        databaseManager: DatabaseManager,
        localSettings: LocalSettings,
    ): RemoteDatabaseSyncService = RemoteDatabaseSyncService(databaseManager, localSettings)

    @Provides
    @SingleIn(AppScope::class)
    fun provideRemoteDatabaseController(
        syncService: RemoteDatabaseSyncService,
        providerFactory: RemoteStorageProviderFactory,
    ): RemoteDatabaseController = RemoteDatabaseController(syncService, providerFactory)

    @Provides
    @SingleIn(AppScope::class)
    fun provideStrategyRemoteConnectionStore(localSettings: LocalSettings): StrategyRemoteConnectionStore =
        StrategyRemoteConnectionStore(localSettings)

    @Provides
    @SingleIn(AppScope::class)
    fun provideStrategySyncController(
        providerFactory: RemoteStorageProviderFactory,
        store: StrategyRemoteConnectionStore,
    ): StrategySyncController = StrategySyncController(providerFactory, store)
}
