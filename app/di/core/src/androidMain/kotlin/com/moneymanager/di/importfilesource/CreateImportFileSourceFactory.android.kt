package com.moneymanager.di.importfilesource

import com.moneymanager.domain.model.importdirectory.ImportDirectory
import com.moneymanager.domain.model.importdirectory.ImportDirectoryProvider
import com.moneymanager.importfilesource.ImportFileSource
import com.moneymanager.importfilesource.ImportFileSourceFactory
import com.moneymanager.importfilesource.localfolder.LocalFolderImportFileSource
import com.moneymanager.localsettings.LocalSettings

@Suppress("ktlint:standard:function-naming")
actual fun createImportFileSourceFactory(localSettings: LocalSettings): ImportFileSourceFactory =
    object : ImportFileSourceFactory {
        override suspend fun create(directory: ImportDirectory): ImportFileSource =
            when (directory.provider) {
                ImportDirectoryProvider.LOCAL -> LocalFolderImportFileSource(directory.folderRef)
                // Drive import directories need the drive.readonly scope via the native Android auth path,
                // which is not wired yet; configure Drive directories from desktop for now.
                ImportDirectoryProvider.GDRIVE ->
                    throw UnsupportedOperationException("Google Drive import directories are not yet supported on Android.")
            }
    }
