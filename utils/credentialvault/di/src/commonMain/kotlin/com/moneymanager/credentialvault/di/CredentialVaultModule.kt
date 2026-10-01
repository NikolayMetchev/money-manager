package com.moneymanager.credentialvault.di

import com.moneymanager.credentialvault.CredentialVault
import com.moneymanager.credentialvault.CredentialVaultImpl
import com.moneymanager.credentialvault.VaultPathResolver
import com.moneymanager.credentialvault.fileVaultStorage
import com.moneymanager.di.params.AppComponentParams
import com.moneymanager.di.scope.AppScope
import com.moneymanager.localsettings.LocalSettings
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * Provides the app-wide [CredentialVault]. AppScope rather than per-database: the vault outlives database
 * switches and is simply re-bound to the next database's file.
 */
@ContributesTo(AppScope::class)
@BindingContainer
object CredentialVaultModule {
    @Provides
    @SingleIn(AppScope::class)
    fun provideCredentialVault(
        params: AppComponentParams,
        localSettings: LocalSettings,
    ): CredentialVault = CredentialVaultImpl(fileVaultStorage(), createVaultPathResolver(params), localSettings)
}

/** The platform's default vault location: the directory holding the database. */
@Suppress("ktlint:standard:function-naming")
expect fun createVaultPathResolver(params: AppComponentParams): VaultPathResolver
