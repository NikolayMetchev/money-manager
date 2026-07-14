package com.moneymanager.importfilesource.di

import com.moneymanager.di.params.AppComponentParams
import com.moneymanager.importfilesource.DriveFolderBrowser
import com.moneymanager.importfilesource.ImportFolder
import com.moneymanager.localsettings.LocalSettings
import com.moneymanager.remotestorage.googledrive.DesktopBrowserLauncher
import com.moneymanager.remotestorage.googledrive.DriveImportFileSource
import com.moneymanager.remotestorage.googledrive.GoogleDriveCredentials
import com.moneymanager.remotestorage.googledrive.GoogleOAuthDefaults
import com.moneymanager.remotestorage.googledrive.googleDriveTokenSource

private fun googleDriveDefaultConfig(): String? =
    if (GoogleOAuthDefaults.isConfigured) {
        GoogleDriveCredentials(GoogleOAuthDefaults.clientId, GoogleOAuthDefaults.clientSecret).toConfig()
    } else {
        null
    }

@Suppress("ktlint:standard:function-naming")
actual fun createDriveFolderBrowser(
    params: AppComponentParams,
    localSettings: LocalSettings,
): DriveFolderBrowser? =
    object : DriveFolderBrowser {
        override val rootFolderId: String = DriveImportFileSource.ROOT_FOLDER_ID
        override val sharedWithMeFolderId: String = DriveImportFileSource.SHARED_WITH_ME_FOLDER_ID

        override suspend fun listChildFolders(
            providerConfig: String?,
            parentId: String,
        ): List<ImportFolder> {
            val tokenSource =
                googleDriveTokenSource(
                    config = providerConfig ?: googleDriveDefaultConfig(),
                    localSettings = localSettings,
                    browser = DesktopBrowserLauncher(),
                )
            if (!tokenSource.isSignedInWithRequiredScopes()) tokenSource.signIn()
            return DriveImportFileSource
                .listChildFolders(tokenSource, parentId)
                .map { ImportFolder(id = it.id, name = it.name) }
        }
    }
