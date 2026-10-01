package com.moneymanager.importfilesource.di

import com.moneymanager.credentialvault.CredentialVault
import com.moneymanager.di.params.AppComponentParams
import com.moneymanager.importfilesource.DriveFolderBrowser

/**
 * Builds the platform's [DriveFolderBrowser] for the add-directory folder picker, or null when this
 * platform can't browse Google Drive (the UI then hides the Drive option). [vault] supplies
 * the Google Drive credentials/token store on JVM; [params] supplies the natively-authorized Drive
 * token source on Android.
 */
@Suppress("ktlint:standard:function-naming")
expect fun createDriveFolderBrowser(
    params: AppComponentParams,
    vault: CredentialVault,
): DriveFolderBrowser?
