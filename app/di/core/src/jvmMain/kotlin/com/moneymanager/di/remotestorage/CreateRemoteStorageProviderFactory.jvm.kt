package com.moneymanager.di.remotestorage

import com.moneymanager.di.AppComponentParams
import com.moneymanager.remotestorage.RemoteStorageProvider
import com.moneymanager.remotestorage.RemoteStorageProviderFactory
import com.moneymanager.remotestorage.RemoteStorageType
import com.moneymanager.remotestorage.googledrive.GOOGLE_DRIVE_PROVIDER_ID
import com.moneymanager.remotestorage.googledrive.GoogleDriveProvider
import com.moneymanager.remotestorage.localfolder.LocalFolderStorageProvider
import java.io.File

@Suppress("ktlint:standard:function-naming")
actual fun createRemoteStorageProviderFactory(params: AppComponentParams): RemoteStorageProviderFactory =
    object : RemoteStorageProviderFactory {
        // Where the Google Drive OAuth client secrets + cached token live by default.
        private val appDir = File(System.getProperty("user.home"), ".moneymanager")

        override fun types(): List<RemoteStorageType> =
            listOf(
                RemoteStorageType(LOCAL_FOLDER_PROVIDER_ID, "Local / Synced Folder", requiresFolder = true),
                RemoteStorageType(GOOGLE_DRIVE_PROVIDER_ID, "Google Drive", requiresFolder = false),
            )

        override fun create(providerId: String, config: String?): RemoteStorageProvider =
            when (providerId) {
                LOCAL_FOLDER_PROVIDER_ID ->
                    LocalFolderStorageProvider.forPath(requireNotNull(config) { "A folder path is required" })
                GOOGLE_DRIVE_PROVIDER_ID -> GoogleDriveProvider.forConfig(config, appDir)
                else -> throw IllegalArgumentException("Unknown remote storage provider: $providerId")
            }
    }
