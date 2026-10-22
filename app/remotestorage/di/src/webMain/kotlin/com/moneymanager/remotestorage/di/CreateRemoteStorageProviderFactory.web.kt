package com.moneymanager.remotestorage.di

import com.moneymanager.credentialvault.CredentialVault
import com.moneymanager.di.params.AppComponentParams
import com.moneymanager.remotestorage.RemoteStorageProvider
import com.moneymanager.remotestorage.RemoteStorageProviderFactory
import com.moneymanager.remotestorage.RemoteStorageType
import com.moneymanager.remotestorage.googledrive.BrowserGoogleAccessTokenSource
import com.moneymanager.remotestorage.googledrive.GOOGLE_DRIVE_PROVIDER_ID
import com.moneymanager.remotestorage.googledrive.GoogleOAuthDefaults
import com.moneymanager.remotestorage.googledrive.googleDriveProvider
import com.moneymanager.remotestorage.googledrive.webClientId

// The browser signs in through Google Identity Services with the build's web client, like Android's native sign-in:
// no stored refresh token, so neither the vault nor a binding's config is used. One token source serves every
// connection (database, strategy library, credential backup), so one sign-in covers them all.
@Suppress("ktlint:standard:function-naming", "UnusedParameter")
actual fun createRemoteStorageProviderFactory(
    params: AppComponentParams,
    vault: CredentialVault,
): RemoteStorageProviderFactory =
    object : RemoteStorageProviderFactory {
        private val tokenSource by lazy { BrowserGoogleAccessTokenSource(GoogleOAuthDefaults.webClientId) }

        // A build without a web client offers no Drive rather than one that can't sign in.
        override fun types(): List<RemoteStorageType> =
            if (GoogleOAuthDefaults.webClientId.isEmpty()) {
                emptyList()
            } else {
                listOf(RemoteStorageType(GOOGLE_DRIVE_PROVIDER_ID, "Google Drive"))
            }

        override fun create(
            providerId: String,
            config: String?,
            subfolder: String?,
        ): RemoteStorageProvider =
            when (providerId) {
                GOOGLE_DRIVE_PROVIDER_ID -> {
                    require(GoogleOAuthDefaults.webClientId.isNotEmpty()) {
                        "Google Drive is not configured in this build (no web OAuth client)."
                    }
                    googleDriveProvider(tokenSource, subfolder)
                }

                else -> {
                    throw IllegalArgumentException("Unknown remote storage provider: $providerId")
                }
            }
    }
