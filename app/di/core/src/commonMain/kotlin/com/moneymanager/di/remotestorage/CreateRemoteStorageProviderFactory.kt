package com.moneymanager.di.remotestorage

import com.moneymanager.di.AppComponentParams
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
