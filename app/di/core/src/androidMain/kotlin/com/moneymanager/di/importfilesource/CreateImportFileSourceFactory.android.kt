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
        // Android reads local folders only; Drive needs the drive.readonly native auth path (not wired
        // yet), so the UI disables download for synced GDRIVE directories rather than failing on click.
        override fun supportsProvider(provider: ImportDirectoryProvider): Boolean = provider == ImportDirectoryProvider.LOCAL

        override suspend fun create(directory: ImportDirectory): ImportFileSource =
            when (directory.provider) {
                ImportDirectoryProvider.LOCAL -> LocalFolderImportFileSource(directory.folderRef)
                ImportDirectoryProvider.GDRIVE ->
                    throw UnsupportedOperationException("Google Drive import directories are not yet supported on Android.")
            }
    }
