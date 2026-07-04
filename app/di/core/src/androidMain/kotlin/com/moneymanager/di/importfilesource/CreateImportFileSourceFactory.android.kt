package com.moneymanager.di.importfilesource

import com.moneymanager.di.AppComponentParams
import com.moneymanager.domain.model.importdirectory.ImportDirectory
import com.moneymanager.domain.model.importdirectory.ImportDirectoryProvider
import com.moneymanager.importfilesource.ImportFileSource
import com.moneymanager.importfilesource.ImportFileSourceFactory
import com.moneymanager.importfilesource.localfolder.LocalFolderImportFileSource
import com.moneymanager.importfilesource.localfolder.SafFolderImportFileSource
import com.moneymanager.localsettings.LocalSettings
import com.moneymanager.remotestorage.googledrive.driveImportFileSource

@Suppress("ktlint:standard:function-naming", "UnusedParameter")
actual fun createImportFileSourceFactory(
    params: AppComponentParams,
    localSettings: LocalSettings,
): ImportFileSourceFactory =
    object : ImportFileSourceFactory {
        override fun supportsProvider(provider: ImportDirectoryProvider): Boolean = true

        override suspend fun create(directory: ImportDirectory): ImportFileSource =
            when (directory.provider) {
                ImportDirectoryProvider.LOCAL ->
                    // SAF-picked folders carry a content:// tree/document URI; plain paths (rows created
                    // before SAF support, or app-readable paths) still go through java.io.
                    if (SafFolderImportFileSource.isDocumentTreeRef(directory.folderRef)) {
                        SafFolderImportFileSource(params.context.contentResolver, directory.folderRef)
                    } else {
                        LocalFolderImportFileSource(directory.folderRef)
                    }
                ImportDirectoryProvider.GDRIVE -> {
                    // directory.providerConfig (bring-your-own OAuth client) is a JVM concept and is
                    // deliberately ignored: Android auth is bound to the app's package + signing key.
                    val tokenSource = params.googleDriveImportTokenSource
                    if (!tokenSource.isSignedInWithRequiredScopes()) tokenSource.signIn()
                    driveImportFileSource(tokenSource, directory.folderRef)
                }
            }
    }
