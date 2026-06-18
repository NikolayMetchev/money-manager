package com.moneymanager.di.remotestorage

import com.moneymanager.database.DatabaseManager
import com.moneymanager.di.AppComponentParams
import com.moneymanager.di.AppScope
import com.moneymanager.localsettings.LocalSettings
import com.moneymanager.remotestorage.RemoteStorageProviderFactory
import com.moneymanager.remotestorage.sync.RemoteDatabaseSyncService
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/** Provides remote-storage (e.g. Google Drive) dependencies. */
@ContributesTo(AppScope::class)
interface RemoteStorageModule {
    @Provides
    @SingleIn(AppScope::class)
    fun provideRemoteStorageProviderFactory(params: AppComponentParams): RemoteStorageProviderFactory =
        createRemoteStorageProviderFactory(params)

    @Provides
    @SingleIn(AppScope::class)
    fun provideRemoteDatabaseSyncService(
        databaseManager: DatabaseManager,
        localSettings: LocalSettings,
    ): RemoteDatabaseSyncService = RemoteDatabaseSyncService(databaseManager, localSettings)
}
