package com.moneymanager.di.remotestorage

import com.moneymanager.di.AppComponentParams
import com.moneymanager.localsettings.LocalSettings
import com.moneymanager.remotestorage.RemoteStorageProviderFactory

/** Provider id for the local/OS-synced folder backend. */
const val LOCAL_FOLDER_PROVIDER_ID = "local-folder"

/**
 * Builds the platform's [RemoteStorageProviderFactory], aggregating every remote-storage backend
 * available on that platform (local-folder + Google Drive). [localSettings] persists the Google Drive
 * refresh token outside the (ephemeral, cloud-backed) database.
 */
@Suppress("ktlint:standard:function-naming")
expect fun createRemoteStorageProviderFactory(
    params: AppComponentParams,
    localSettings: LocalSettings,
): RemoteStorageProviderFactory
