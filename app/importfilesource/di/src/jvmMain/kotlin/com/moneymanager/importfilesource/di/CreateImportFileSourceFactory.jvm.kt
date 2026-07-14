package com.moneymanager.importfilesource.di

import com.moneymanager.di.params.AppComponentParams
import com.moneymanager.domain.model.importdirectory.ImportDirectory
import com.moneymanager.domain.model.importdirectory.ImportDirectoryProvider
import com.moneymanager.importfilesource.ImportFileSource
import com.moneymanager.importfilesource.ImportFileSourceFactory
import com.moneymanager.importfilesource.localfolder.LocalFolderImportFileSource
import com.moneymanager.localsettings.LocalSettings
import com.moneymanager.remotestorage.googledrive.DesktopBrowserLauncher
import com.moneymanager.remotestorage.googledrive.GoogleDriveCredentials
import com.moneymanager.remotestorage.googledrive.GoogleOAuthDefaults
import com.moneymanager.remotestorage.googledrive.driveImportFileSource
import com.moneymanager.remotestorage.googledrive.googleDriveTokenSource

// The shipped desktop OAuth client (build-time injected), serialized as provider config — or null when
// this build wasn't given the secret.
private fun googleDriveDefaultConfig(): String? =
    if (GoogleOAuthDefaults.isConfigured) {
        GoogleDriveCredentials(GoogleOAuthDefaults.clientId, GoogleOAuthDefaults.clientSecret).toConfig()
    } else {
        null
    }

@Suppress("ktlint:standard:function-naming")
actual fun createImportFileSourceFactory(
    params: AppComponentParams,
    localSettings: LocalSettings,
): ImportFileSourceFactory =
    object : ImportFileSourceFactory {
        // Desktop reads both local folders and Google Drive.
        override fun supportsProvider(provider: ImportDirectoryProvider): Boolean = true

        override suspend fun create(directory: ImportDirectory): ImportFileSource =
            when (directory.provider) {
                ImportDirectoryProvider.LOCAL -> LocalFolderImportFileSource(directory.folderRef)
                ImportDirectoryProvider.GDRIVE -> {
                    val tokenSource =
                        googleDriveTokenSource(
                            config = directory.providerConfig ?: googleDriveDefaultConfig(),
                            localSettings = localSettings,
                            browser = DesktopBrowserLauncher(),
                        )
                    if (!tokenSource.isSignedInWithRequiredScopes()) tokenSource.signIn()
                    driveImportFileSource(tokenSource, directory.folderRef)
                }
            }
    }
