package com.moneymanager.importfilesource.di

import com.moneymanager.di.params.AppComponentParams
import com.moneymanager.importfilesource.ImportFileSourceFactory
import com.moneymanager.localsettings.LocalSettings

/**
 * Builds the platform's [ImportFileSourceFactory], which resolves a read-only file source for a
 * configured import directory (local folder or Google Drive folder). [localSettings] supplies the
 * Google Drive credentials/token store on JVM; [params] supplies the Android `Context` (SAF folder
 * access) and the natively-authorized Drive token source.
 */
@Suppress("ktlint:standard:function-naming")
expect fun createImportFileSourceFactory(
    params: AppComponentParams,
    localSettings: LocalSettings,
): ImportFileSourceFactory
