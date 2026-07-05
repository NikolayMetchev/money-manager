package com.moneymanager.di.importfilesource

import com.moneymanager.di.AppComponentParams
import com.moneymanager.importfilesource.DriveFolderBrowser
import com.moneymanager.localsettings.LocalSettings

/**
 * Builds the platform's [DriveFolderBrowser] for the add-directory folder picker, or null when this
 * platform can't browse Google Drive (the UI then hides the Drive option). [localSettings] supplies
 * the Google Drive credentials/token store on JVM; [params] supplies the natively-authorized Drive
 * token source on Android.
 */
@Suppress("ktlint:standard:function-naming")
expect fun createDriveFolderBrowser(
    params: AppComponentParams,
    localSettings: LocalSettings,
): DriveFolderBrowser?
