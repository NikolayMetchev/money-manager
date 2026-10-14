package com.moneymanager.remotestorage.di

import com.moneymanager.credentialvault.CredentialVault
import com.moneymanager.di.params.AppComponentParams
import com.moneymanager.remotestorage.RemoteStorageProviderFactory

@Suppress("ktlint:standard:function-naming")
actual fun createRemoteStorageProviderFactory(
    params: AppComponentParams,
    vault: CredentialVault,
): RemoteStorageProviderFactory = loopbackRemoteStorageProviderFactory(vault, params.browserLauncher)
