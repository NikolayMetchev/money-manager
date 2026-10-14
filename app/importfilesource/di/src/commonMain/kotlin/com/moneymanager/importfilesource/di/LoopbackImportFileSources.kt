package com.moneymanager.importfilesource.di

import com.moneymanager.credentialvault.CredentialVault
import com.moneymanager.domain.model.importdirectory.ImportDirectory
import com.moneymanager.domain.model.importdirectory.ImportDirectoryProvider
import com.moneymanager.importfilesource.DriveFolderBrowser
import com.moneymanager.importfilesource.ImportFileSource
import com.moneymanager.importfilesource.ImportFileSourceFactory
import com.moneymanager.importfilesource.ImportFolder
import com.moneymanager.importfilesource.localfolder.LocalFolderImportFileSource
import com.moneymanager.remotestorage.googledrive.BrowserLauncher
import com.moneymanager.remotestorage.googledrive.DriveImportFileSource
import com.moneymanager.remotestorage.googledrive.GoogleAccessTokenSource
import com.moneymanager.remotestorage.googledrive.GoogleOAuthDefaults
import com.moneymanager.remotestorage.googledrive.driveImportFileSource
import com.moneymanager.remotestorage.googledrive.googleDriveTokenSource

// The desktop JVM app and the native CLI read local folders and Google Drive, signing in to Drive through the
// installed-app loopback flow with consent opened by a BrowserLauncher. Android has its own SAF and token sources.

private suspend fun signedInTokenSource(
    providerConfig: String?,
    vault: CredentialVault,
    browser: BrowserLauncher,
): GoogleAccessTokenSource {
    val tokenSource =
        googleDriveTokenSource(
            config = providerConfig ?: GoogleOAuthDefaults.providerConfig(),
            vault = vault,
            browser = browser,
        )
    if (!tokenSource.isSignedInWithRequiredScopes()) tokenSource.signIn()
    return tokenSource
}

internal fun loopbackImportFileSourceFactory(
    vault: CredentialVault,
    browser: BrowserLauncher,
): ImportFileSourceFactory =
    object : ImportFileSourceFactory {
        override suspend fun create(directory: ImportDirectory): ImportFileSource =
            when (directory.provider) {
                ImportDirectoryProvider.LOCAL -> LocalFolderImportFileSource(directory.folderRef)
                ImportDirectoryProvider.GDRIVE ->
                    driveImportFileSource(signedInTokenSource(directory.providerConfig, vault, browser), directory.folderRef)
            }
    }

internal fun loopbackDriveFolderBrowser(
    vault: CredentialVault,
    browser: BrowserLauncher,
): DriveFolderBrowser =
    object : DriveFolderBrowser {
        override val rootFolderId: String = DriveImportFileSource.ROOT_FOLDER_ID
        override val sharedWithMeFolderId: String = DriveImportFileSource.SHARED_WITH_ME_FOLDER_ID

        override suspend fun listChildFolders(
            providerConfig: String?,
            parentId: String,
        ): List<ImportFolder> =
            DriveImportFileSource
                .listChildFolders(signedInTokenSource(providerConfig, vault, browser), parentId)
                .map { ImportFolder(id = it.id, name = it.name) }
    }
