package com.moneymanager.remotestorage.di

import com.moneymanager.credentialvault.CredentialVault
import com.moneymanager.di.params.AppComponentParams
import com.moneymanager.remotestorage.RemoteStorageProviderFactory

/**
 * Builds the platform's [RemoteStorageProviderFactory] (currently the Google Drive backend).
 * [vault] holds the Google Drive refresh token outside the (ephemeral, cloud-backed) database.
 */
@Suppress("ktlint:standard:function-naming")
expect fun createRemoteStorageProviderFactory(
    params: AppComponentParams,
    vault: CredentialVault,
): RemoteStorageProviderFactory
