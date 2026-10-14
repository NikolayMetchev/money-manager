package com.moneymanager.credentialvault.di

import com.moneymanager.credentialvault.VaultPathResolver
import com.moneymanager.credentialvault.vaultPathBesideDatabaseFile
import com.moneymanager.di.params.AppComponentParams

// A native database key is the database file's path, as on the JVM.
@Suppress("ktlint:standard:function-naming", "UnusedParameter")
actual fun createVaultPathResolver(params: AppComponentParams): VaultPathResolver = VaultPathResolver(::vaultPathBesideDatabaseFile)
