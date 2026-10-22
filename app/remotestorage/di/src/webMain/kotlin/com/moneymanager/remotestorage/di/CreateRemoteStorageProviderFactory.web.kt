package com.moneymanager.remotestorage.di

import com.moneymanager.credentialvault.CredentialVault
import com.moneymanager.di.params.AppComponentParams
import com.moneymanager.remotestorage.RemoteStorageProvider
import com.moneymanager.remotestorage.RemoteStorageProviderFactory
import com.moneymanager.remotestorage.RemoteStorageType

// The browser can't receive the loopback flow's redirect, so it offers no cloud storage until it signs in to Google
// its own way.
@Suppress("ktlint:standard:function-naming", "UnusedParameter")
actual fun createRemoteStorageProviderFactory(
    params: AppComponentParams,
    vault: CredentialVault,
): RemoteStorageProviderFactory =
    object : RemoteStorageProviderFactory {
        override fun types(): List<RemoteStorageType> = emptyList()

        override fun create(
            providerId: String,
            config: String?,
            subfolder: String?,
        ): RemoteStorageProvider = throw IllegalArgumentException("Unknown remote storage provider: $providerId")
    }
