package com.moneymanager.credentialvault.di

import com.moneymanager.credentialvault.DEFAULT_VAULT_FILE_NAME
import com.moneymanager.credentialvault.LocalStorageVaultStorage
import com.moneymanager.credentialvault.VaultPathResolver
import com.moneymanager.credentialvault.VaultStorage
import com.moneymanager.di.params.AppComponentParams

// The browser's databases all sit in one place (the worker's OPFS pool), so like databases sharing a folder
// elsewhere, they share one vault.
@Suppress("ktlint:standard:function-naming", "UnusedParameter")
actual fun createVaultPathResolver(params: AppComponentParams): VaultPathResolver = VaultPathResolver { "/$DEFAULT_VAULT_FILE_NAME" }

@Suppress("ktlint:standard:function-naming")
actual fun createVaultStorage(): VaultStorage = LocalStorageVaultStorage()
