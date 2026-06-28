package com.moneymanager.di.importfilesource

import com.moneymanager.importfilesource.DriveFolderBrowser
import com.moneymanager.importfilesource.ImportFolder
import com.moneymanager.localsettings.LocalSettings

@Suppress("ktlint:standard:function-naming")
actual fun createDriveFolderBrowser(localSettings: LocalSettings): DriveFolderBrowser =
    object : DriveFolderBrowser {
        override val rootFolderId: String = "root"
        override val sharedWithMeFolderId: String = "sharedWithMe"

        // Drive import directories need the drive.readonly scope via the native Android auth path, which
        // is not wired yet; configure Drive directories from desktop for now.
        override suspend fun listChildFolders(
            providerConfig: String?,
            parentId: String,
        ): List<ImportFolder> = throw UnsupportedOperationException("Google Drive folder browsing is not yet supported on Android.")
    }
