package com.moneymanager.remotestorage.di

import com.moneymanager.di.params.AppComponentParams
import com.moneymanager.localsettings.LocalSettings
import com.moneymanager.remotestorage.RemoteStorageProviderFactory

/**
 * Builds the platform's [RemoteStorageProviderFactory] (currently the Google Drive backend).
 * [localSettings] persists the Google Drive refresh token outside the (ephemeral, cloud-backed) database.
 */
@Suppress("ktlint:standard:function-naming")
expect fun createRemoteStorageProviderFactory(
    params: AppComponentParams,
    localSettings: LocalSettings,
): RemoteStorageProviderFactory
