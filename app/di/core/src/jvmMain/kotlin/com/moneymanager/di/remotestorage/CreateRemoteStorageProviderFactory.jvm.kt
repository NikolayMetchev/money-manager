package com.moneymanager.di.remotestorage

import com.moneymanager.di.AppComponentParams
import com.moneymanager.localsettings.LocalSettings
import com.moneymanager.remotestorage.RemoteStorageProvider
import com.moneymanager.remotestorage.RemoteStorageProviderFactory
import com.moneymanager.remotestorage.RemoteStorageType
import com.moneymanager.remotestorage.googledrive.DesktopBrowserLauncher
import com.moneymanager.remotestorage.googledrive.GOOGLE_DRIVE_PROVIDER_ID
import com.moneymanager.remotestorage.googledrive.GoogleDriveCredentials
import com.moneymanager.remotestorage.googledrive.GoogleOAuthDefaults
import com.moneymanager.remotestorage.googledrive.googleDriveProvider

// The shipped desktop OAuth client (build-time injected), serialized as provider config — or null when
// this build wasn't given the secret, in which case sign-in surfaces a "not configured" error.
private fun googleDriveDefaultConfig(): String? =
    if (GoogleOAuthDefaults.isConfigured) {
        GoogleDriveCredentials(GoogleOAuthDefaults.clientId, GoogleOAuthDefaults.clientSecret).toConfig()
    } else {
        null
    }

@Suppress("ktlint:standard:function-naming")
actual fun createRemoteStorageProviderFactory(
    params: AppComponentParams,
    localSettings: LocalSettings,
): RemoteStorageProviderFactory =
    object : RemoteStorageProviderFactory {
        // Desktop ships its own OAuth client, so no bring-your-own config step is needed (like Android).
        override fun types(): List<RemoteStorageType> = listOf(RemoteStorageType(GOOGLE_DRIVE_PROVIDER_ID, "Google Drive"))

        override fun create(
            providerId: String,
            config: String?,
        ): RemoteStorageProvider =
            when (providerId) {
                GOOGLE_DRIVE_PROVIDER_ID ->
                    googleDriveProvider(config ?: googleDriveDefaultConfig(), localSettings, DesktopBrowserLauncher())
                else -> throw IllegalArgumentException("Unknown remote storage provider: $providerId")
            }
    }
