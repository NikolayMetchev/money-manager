package com.moneymanager.di.importfilesource

import com.moneymanager.importfilesource.ImportFileSourceFactory
import com.moneymanager.localsettings.LocalSettings

/**
 * Builds the platform's [ImportFileSourceFactory], which resolves a read-only file source for a
 * configured import directory (local folder or Google Drive folder). [localSettings] supplies the
 * Google Drive credentials/token store for Drive directories.
 */
@Suppress("ktlint:standard:function-naming")
expect fun createImportFileSourceFactory(localSettings: LocalSettings): ImportFileSourceFactory
