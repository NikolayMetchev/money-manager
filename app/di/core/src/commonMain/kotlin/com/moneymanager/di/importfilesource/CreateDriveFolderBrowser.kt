package com.moneymanager.di.importfilesource

import com.moneymanager.importfilesource.DriveFolderBrowser
import com.moneymanager.localsettings.LocalSettings

/**
 * Builds the platform's [DriveFolderBrowser] for the add-directory folder picker. [localSettings]
 * supplies the Google Drive credentials/token store.
 */
@Suppress("ktlint:standard:function-naming")
expect fun createDriveFolderBrowser(localSettings: LocalSettings): DriveFolderBrowser
