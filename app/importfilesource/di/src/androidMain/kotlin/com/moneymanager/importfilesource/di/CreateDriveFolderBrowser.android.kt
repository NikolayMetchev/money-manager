package com.moneymanager.importfilesource.di

import com.moneymanager.credentialvault.CredentialVault
import com.moneymanager.di.params.AppComponentParams
import com.moneymanager.importfilesource.DriveFolderBrowser
import com.moneymanager.importfilesource.ImportFolder
import com.moneymanager.remotestorage.googledrive.DriveImportFileSource

@Suppress("ktlint:standard:function-naming", "UnusedParameter")
actual fun createDriveFolderBrowser(
    params: AppComponentParams,
    vault: CredentialVault,
): DriveFolderBrowser? =
    object : DriveFolderBrowser {
        override val rootFolderId: String = DriveImportFileSource.ROOT_FOLDER_ID
        override val sharedWithMeFolderId: String = DriveImportFileSource.SHARED_WITH_ME_FOLDER_ID

        override suspend fun listChildFolders(parentId: String): List<ImportFolder> {
            val tokenSource = params.googleDriveImportTokenSource
            if (!tokenSource.isSignedInWithRequiredScopes()) tokenSource.signIn()
            return DriveImportFileSource
                .listChildFolders(tokenSource, parentId)
                .map { ImportFolder(id = it.id, name = it.name) }
        }
    }
