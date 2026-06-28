package com.moneymanager.di.importfilesource

import com.moneymanager.importfilesource.DriveFolderBrowser
import com.moneymanager.localsettings.LocalSettings

/**
 * Builds the platform's [DriveFolderBrowser] for the add-directory folder picker, or null when this
 * platform can't browse Google Drive (the UI then hides the Drive option). [localSettings] supplies
 * the Google Drive credentials/token store.
 */
@Suppress("ktlint:standard:function-naming")
expect fun createDriveFolderBrowser(localSettings: LocalSettings): DriveFolderBrowser?
