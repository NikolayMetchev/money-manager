package com.moneymanager.remotestorage.di

import com.moneymanager.credentialvault.CredentialVault
import com.moneymanager.di.params.AppComponentParams
import com.moneymanager.remotestorage.RemoteStorageProviderFactory
import com.moneymanager.remotestorage.googledrive.loopback.loopbackRemoteStorageProviderFactory

@Suppress("ktlint:standard:function-naming")
actual fun createRemoteStorageProviderFactory(
    params: AppComponentParams,
    vault: CredentialVault,
): RemoteStorageProviderFactory = loopbackRemoteStorageProviderFactory(vault, params.browserLauncher)
