package com.moneymanager.di.importfilesource

import com.moneymanager.importfilesource.DriveFolderBrowser
import com.moneymanager.localsettings.LocalSettings

// Drive folder browsing needs the drive.readonly scope via the native Android auth path, which is not
// wired yet. Returning null makes the add-directory dialog hide the Google Drive option on Android
// (configure Drive directories from desktop for now) instead of offering a picker that fails.
@Suppress("ktlint:standard:function-naming")
actual fun createDriveFolderBrowser(localSettings: LocalSettings): DriveFolderBrowser? = null
