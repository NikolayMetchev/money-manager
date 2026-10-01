package com.moneymanager.credentialvault.di

import com.moneymanager.credentialvault.VaultPathResolver
import com.moneymanager.credentialvault.vaultPathBesideDatabaseFile
import com.moneymanager.di.params.AppComponentParams

// A JVM database key is the database file's absolute path.
@Suppress("ktlint:standard:function-naming", "UnusedParameter")
actual fun createVaultPathResolver(params: AppComponentParams): VaultPathResolver = VaultPathResolver(::vaultPathBesideDatabaseFile)
