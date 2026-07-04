package com.moneymanager.di.importfilesource

import com.moneymanager.di.AppComponentParams
import com.moneymanager.importfilesource.DriveFolderBrowser
import com.moneymanager.importfilesource.ImportFolder
import com.moneymanager.localsettings.LocalSettings
import com.moneymanager.remotestorage.googledrive.DriveImportFileSource

@Suppress("ktlint:standard:function-naming", "UnusedParameter")
actual fun createDriveFolderBrowser(
    params: AppComponentParams,
    localSettings: LocalSettings,
): DriveFolderBrowser? =
    object : DriveFolderBrowser {
        override val rootFolderId: String = DriveImportFileSource.ROOT_FOLDER_ID
        override val sharedWithMeFolderId: String = DriveImportFileSource.SHARED_WITH_ME_FOLDER_ID

        // providerConfig (bring-your-own OAuth client) is a JVM concept and is deliberately ignored:
        // Android auth is bound to the app's package + signing key via the GMS AuthorizationClient.
        override suspend fun listChildFolders(
            providerConfig: String?,
            parentId: String,
        ): List<ImportFolder> {
            val tokenSource = params.googleDriveImportTokenSource
            if (!tokenSource.isSignedInWithRequiredScopes()) tokenSource.signIn()
            return DriveImportFileSource
                .listChildFolders(tokenSource, parentId)
                .map { ImportFolder(id = it.id, name = it.name) }
        }
    }
