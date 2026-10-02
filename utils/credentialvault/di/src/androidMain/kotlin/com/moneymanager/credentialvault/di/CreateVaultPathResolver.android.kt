package com.moneymanager.credentialvault.di

import com.moneymanager.credentialvault.VaultPathResolver
import com.moneymanager.credentialvault.vaultPathBesideDatabaseFile
import com.moneymanager.di.params.AppComponentParams

// An Android database key is a database name, resolved against the app's databases directory.
@Suppress("ktlint:standard:function-naming")
actual fun createVaultPathResolver(params: AppComponentParams): VaultPathResolver =
    VaultPathResolver { databaseName -> vaultPathBesideDatabaseFile(params.context.getDatabasePath(databaseName).absolutePath) }
