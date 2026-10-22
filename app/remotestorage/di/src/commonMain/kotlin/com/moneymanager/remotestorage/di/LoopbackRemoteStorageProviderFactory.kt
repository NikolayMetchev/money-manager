package com.moneymanager.remotestorage.di

import com.moneymanager.credentialvault.CredentialVault
import com.moneymanager.remotestorage.RemoteStorageProvider
import com.moneymanager.remotestorage.RemoteStorageProviderFactory
import com.moneymanager.remotestorage.RemoteStorageType
import com.moneymanager.remotestorage.googledrive.BrowserLauncher
import com.moneymanager.remotestorage.googledrive.GOOGLE_DRIVE_PROVIDER_ID
import com.moneymanager.remotestorage.googledrive.GoogleOAuthDefaults
import com.moneymanager.remotestorage.googledrive.loopback.googleDriveProvider

/**
 * Google Drive signed in through the installed-app loopback flow, consent opened by [browser]. The desktop JVM
 * app and the native CLI share it; Android signs in through its own token source instead.
 */
internal fun loopbackRemoteStorageProviderFactory(
    vault: CredentialVault,
    browser: BrowserLauncher,
): RemoteStorageProviderFactory =
    object : RemoteStorageProviderFactory {
        // The build ships its own OAuth client, so no bring-your-own config step is needed (like Android).
        override fun types(): List<RemoteStorageType> = listOf(RemoteStorageType(GOOGLE_DRIVE_PROVIDER_ID, "Google Drive"))

        override fun create(
            providerId: String,
            config: String?,
            subfolder: String?,
        ): RemoteStorageProvider =
            when (providerId) {
                GOOGLE_DRIVE_PROVIDER_ID -> {
                    googleDriveProvider(config ?: GoogleOAuthDefaults.providerConfig(), vault, browser, subfolder)
                }

                else -> {
                    throw IllegalArgumentException("Unknown remote storage provider: $providerId")
                }
            }
    }
