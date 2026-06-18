package com.moneymanager.remotestorage.localfolder

import com.moneymanager.remotestorage.RemoteFile
import com.moneymanager.remotestorage.RemoteStorageException
import com.moneymanager.remotestorage.RemoteStorageProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A [RemoteStorageProvider] that stores archives as files in a local directory [rootDir]. Pointing
 * [rootDir] at a folder that an OS sync client mirrors (Dropbox, Google Drive desktop, OneDrive, ...)
 * effectively syncs the database via that client. The file id is the archive's filename within
 * [rootDir].
 */
class LocalFolderStorageProvider(
    private val rootDir: File,
    override val id: String = "local-folder",
    override val displayName: String = "Local Folder",
) : RemoteStorageProvider {
    override suspend fun isSignedIn(): Boolean = true

    override suspend fun signIn() {
        withContext(Dispatchers.IO) { rootDir.mkdirs() }
    }

    override suspend fun signOut() = Unit

    override suspend fun list(): List<RemoteFile> =
        withContext(Dispatchers.IO) {
            rootDir.listFiles()?.filter { it.isFile }?.map { it.toRemoteFile() } ?: emptyList()
        }

    override suspend fun download(fileId: String): ByteArray =
        withContext(Dispatchers.IO) {
            val file = File(rootDir, fileId)
            if (!file.isFile) throw RemoteStorageException("No such file: $fileId")
            file.readBytes()
        }

    override suspend fun upload(fileId: String?, name: String, bytes: ByteArray): RemoteFile =
        withContext(Dispatchers.IO) {
            rootDir.mkdirs()
            val target = File(rootDir, fileId ?: name)
            target.writeBytes(bytes)
            target.toRemoteFile()
        }

    override suspend fun delete(fileId: String) {
        withContext(Dispatchers.IO) { File(rootDir, fileId).delete() }
    }

    private fun File.toRemoteFile() = RemoteFile(id = name, name = name, sizeBytes = length(), modifiedAtEpochMs = lastModified())
}
