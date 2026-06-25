package com.moneymanager.di.remotestorage

import com.moneymanager.di.AppComponentParams
import com.moneymanager.localsettings.LocalSettings
import com.moneymanager.remotestorage.RemoteStorageProvider
import com.moneymanager.remotestorage.RemoteStorageProviderFactory
import com.moneymanager.remotestorage.RemoteStorageType
import com.moneymanager.remotestorage.googledrive.GOOGLE_DRIVE_PROVIDER_ID
import com.moneymanager.remotestorage.googledrive.googleDriveProvider

// localSettings is unused on Android: the native AuthorizationClient token source holds no persisted
// refresh token (GMS re-issues access tokens silently), unlike the JVM loopback flow.
@Suppress("ktlint:standard:function-naming", "UnusedParameter")
actual fun createRemoteStorageProviderFactory(
    params: AppComponentParams,
    localSettings: LocalSettings,
): RemoteStorageProviderFactory =
    object : RemoteStorageProviderFactory {
        // Android authenticates natively (AuthorizationClient), so no bring-your-own config is needed.
        override fun types(): List<RemoteStorageType> = listOf(RemoteStorageType(GOOGLE_DRIVE_PROVIDER_ID, "Google Drive"))

        override fun create(
            providerId: String,
            config: String?,
        ): RemoteStorageProvider =
            when (providerId) {
                // config is ignored on Android — auth is tied to the app's registered OAuth client.
                GOOGLE_DRIVE_PROVIDER_ID -> googleDriveProvider(params.googleTokenSource)
                else -> throw IllegalArgumentException("Unknown remote storage provider: $providerId")
            }
    }
