package com.moneymanager.di.remotestorage

import com.moneymanager.di.AppComponentParams
import com.moneymanager.localsettings.LocalSettings
import com.moneymanager.remotestorage.RemoteStorageProvider
import com.moneymanager.remotestorage.RemoteStorageProviderFactory
import com.moneymanager.remotestorage.RemoteStorageType
import com.moneymanager.remotestorage.googledrive.DesktopBrowserLauncher
import com.moneymanager.remotestorage.googledrive.GOOGLE_DRIVE_PROVIDER_ID
import com.moneymanager.remotestorage.googledrive.googleDriveProvider

@Suppress("ktlint:standard:function-naming")
actual fun createRemoteStorageProviderFactory(
    params: AppComponentParams,
    localSettings: LocalSettings,
): RemoteStorageProviderFactory =
    object : RemoteStorageProviderFactory {
        override fun types(): List<RemoteStorageType> = listOf(RemoteStorageType(GOOGLE_DRIVE_PROVIDER_ID, "Google Drive"))

        override fun create(
            providerId: String,
            config: String?,
        ): RemoteStorageProvider =
            when (providerId) {
                GOOGLE_DRIVE_PROVIDER_ID -> googleDriveProvider(config, localSettings, DesktopBrowserLauncher())
                else -> throw IllegalArgumentException("Unknown remote storage provider: $providerId")
            }
    }
