package com.moneymanager.importfilesource.di

import com.moneymanager.credentialvault.CredentialVault
import com.moneymanager.di.params.AppComponentParams
import com.moneymanager.importfilesource.DriveFolderBrowser

@Suppress("ktlint:standard:function-naming")
actual fun createDriveFolderBrowser(
    params: AppComponentParams,
    vault: CredentialVault,
): DriveFolderBrowser? = loopbackDriveFolderBrowser(vault, params.browserLauncher)
