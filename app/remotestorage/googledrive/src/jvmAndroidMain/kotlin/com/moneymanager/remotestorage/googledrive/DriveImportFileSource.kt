package com.moneymanager.remotestorage.googledrive

import com.moneymanager.importfilesource.ImportFileEntry
import com.moneymanager.importfilesource.ImportFileSource
import com.moneymanager.importfilesource.ImportSubfolder
import com.moneymanager.remotestorage.RemoteStorageException
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.time.Instant
import kotlinx.serialization.json.Json as JsonFormat

/**
 * Lists + downloads files from a specific Google Drive folder (by id) for import. Reuses the
 * authenticated Ktor client from a [GoogleAccessTokenSource] signed in with [DRIVE_READONLY_SCOPE]
 * (the user's dropped CSV/QIF files were not created by the app, so [DRIVE_FILE_SCOPE] cannot see
 * them). Non-recursive: only files directly in [folderId]; the file ref is the Drive file id.
 */
class DriveImportFileSource(
    private val tokenSource: GoogleAccessTokenSource,
    private val folderId: String,
) : ImportFileSource {
    private val httpClient = tokenSource.httpClient

    override suspend fun list(): List<ImportFileEntry> =
        withContext(Dispatchers.IO) {
            val driveQuery = "'$folderId' in parents and trashed = false and mimeType != '$FOLDER_MIME_TYPE'"
            val body =
                httpClient
                    .get(FILES_ENDPOINT) {
                        url {
                            parameters.append("q", driveQuery)
                            parameters.append("spaces", "drive")
                            parameters.append("fields", "files($FILE_FIELDS)")
                        }
                    }.requireBody("list Google Drive folder")
            json.decodeFromString<DriveFileList>(body).files.map { it.toEntry() }
        }

    override suspend fun listSubfolders(): List<ImportSubfolder> =
        withContext(Dispatchers.IO) {
            listChildFolders(tokenSource, folderId).map { ImportSubfolder(ref = it.id, name = it.name) }
        }

    override suspend fun download(fileRef: String): ByteArray =
        withContext(Dispatchers.IO) {
            val response =
                httpClient.get("$FILES_ENDPOINT/$fileRef") {
                    url {
                        parameters.append("alt", "media")
                        // Needed for files living in / shared from a Shared Drive.
                        parameters.append("supportsAllDrives", "true")
                        // Bypass Drive's "this file may be abusive" 403 for the user's own statements.
                        parameters.append("acknowledgeAbuse", "true")
                    }
                }
            if (!response.status.isSuccess()) {
                val hint =
                    if (response.status.value == HTTP_FORBIDDEN) {
                        " — the file's owner may have disabled downloads for viewers"
                    } else {
                        ""
                    }
                throw RemoteStorageException("Failed to download $fileRef from Google Drive (${response.status.value})$hint")
            }
            response.body<ByteArray>()
        }

    private suspend fun HttpResponse.requireBody(action: String): String {
        if (!status.isSuccess()) throw RemoteStorageException("Failed to $action (${status.value})")
        return bodyAsText()
    }

    private fun DriveFile.toEntry() =
        ImportFileEntry(
            ref = id,
            name = name,
            lastModifiedEpochMs = modifiedTime?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() },
            sizeBytes = size?.toLongOrNull(),
        )

    @Serializable
    private data class DriveFile(
        val id: String,
        val name: String = "",
        val size: String? = null,
        val modifiedTime: String? = null,
    )

    @Serializable
    private data class DriveFileList(
        val files: List<DriveFile> = emptyList(),
    )

    companion object {
        private const val FILES_ENDPOINT = "https://www.googleapis.com/drive/v3/files"
        private const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
        private const val FILE_FIELDS = "id,name,size,modifiedTime"
        private const val HTTP_FORBIDDEN = 403
        private val json = JsonFormat { ignoreUnknownKeys = true }

        /** A Drive folder the user can pick as an import directory. */
        @Serializable
        data class DriveFolder(
            val id: String,
            val name: String = "",
        )

        @Serializable
        private data class DriveFolderList(
            val files: List<DriveFolder> = emptyList(),
        )

        /** The My Drive root alias, used as the starting parent for navigation under "My Drive". */
        const val ROOT_FOLDER_ID = "root"

        /**
         * Sentinel parent id for the "Shared with me" root: not a real Drive folder id, so
         * [listChildFolders] maps it to a `sharedWithMe = true` query. Items shared to the user are not
         * children of My Drive root, so they need their own root (mirroring Drive's UI).
         */
        const val SHARED_WITH_ME_FOLDER_ID = "sharedWithMe"

        /**
         * Lists the immediate subfolders of [parentId], to power a hierarchical folder picker. Use
         * [ROOT_FOLDER_ID] for My Drive's top level or [SHARED_WITH_ME_FOLDER_ID] for "Shared with me".
         * `includeItemsFromAllDrives` also surfaces Shared Drive content. Requires a token source signed
         * in with [DRIVE_READONLY_SCOPE].
         */
        suspend fun listChildFolders(
            tokenSource: GoogleAccessTokenSource,
            parentId: String,
        ): List<DriveFolder> =
            withContext(Dispatchers.IO) {
                val driveQuery =
                    if (parentId == SHARED_WITH_ME_FOLDER_ID) {
                        "sharedWithMe = true and mimeType = '$FOLDER_MIME_TYPE' and trashed = false"
                    } else {
                        "'$parentId' in parents and mimeType = '$FOLDER_MIME_TYPE' and trashed = false"
                    }
                queryFolders(tokenSource, driveQuery).sortedBy { it.name.lowercase() }
            }

        private suspend fun queryFolders(
            tokenSource: GoogleAccessTokenSource,
            driveQuery: String,
        ): List<DriveFolder> {
            val response =
                tokenSource.httpClient.get(FILES_ENDPOINT) {
                    url {
                        parameters.append("q", driveQuery)
                        parameters.append("spaces", "drive")
                        parameters.append("fields", "files(id,name)")
                        parameters.append("orderBy", "name")
                        // Surface Shared Drive content too (no-op for plain My Drive accounts). Not using
                        // corpora=allDrives: it conflicts with the 'root' in parents / sharedWithMe terms.
                        parameters.append("includeItemsFromAllDrives", "true")
                        parameters.append("supportsAllDrives", "true")
                    }
                }
            if (!response.status.isSuccess()) {
                throw RemoteStorageException("Failed to list Google Drive folders (${response.status.value})")
            }
            return json.decodeFromString<DriveFolderList>(response.bodyAsText()).files
        }
    }
}
