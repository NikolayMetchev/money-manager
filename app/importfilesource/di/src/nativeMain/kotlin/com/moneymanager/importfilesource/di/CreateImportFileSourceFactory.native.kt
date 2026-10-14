package com.moneymanager.importfilesource.di

import com.moneymanager.credentialvault.CredentialVault
import com.moneymanager.di.params.AppComponentParams
import com.moneymanager.importfilesource.ImportFileSourceFactory

@Suppress("ktlint:standard:function-naming")
actual fun createImportFileSourceFactory(
    params: AppComponentParams,
    vault: CredentialVault,
): ImportFileSourceFactory = loopbackImportFileSourceFactory(vault, params.browserLauncher)
