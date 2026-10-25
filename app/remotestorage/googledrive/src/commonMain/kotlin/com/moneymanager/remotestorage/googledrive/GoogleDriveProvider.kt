package com.moneymanager.remotestorage.googledrive

import com.moneymanager.coroutines.ioDispatcher
import com.moneymanager.remotestorage.RemoteFile
import com.moneymanager.remotestorage.RemoteStorageException
import com.moneymanager.remotestorage.RemoteStorageProvider
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.ContentType.Application.Json
import io.ktor.http.ContentType.Application.OctetStream
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.withCharset
import io.ktor.utils.io.charsets.Charsets
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json as JsonFormat

/**
 * A `RemoteStorageProvider` backed by Google Drive, spoken directly over the Drive REST API v3 with a
 * shared Ktor client so the REST surface runs identically on JVM, Android and native (the Google Java
 * SDK's OAuth is JVM-only).
 *
 * Sign-in and access-token acquisition are delegated to a platform [GoogleAccessTokenSource] (JVM =
 * installed-app loopback + refresh token; Android = native `AuthorizationClient`); the provider just
 * reads [tokenSource]'s pre-authenticated [httpClient] for every Drive call.
 *
 * Archives live in a "[GOOGLE_DRIVE_FOLDER_NAME]" folder (created on first use). Least privilege: only
 * [DRIVE_FILE_SCOPE]; uploads are tagged with an appProperty, and updates happen in place so the remote
 * file id stays stable across syncs.
 */
class GoogleDriveProvider(
    private val tokenSource: GoogleAccessTokenSource,
    override val id: String = GOOGLE_DRIVE_PROVIDER_ID,
    override val displayName: String = "Google Drive",
    // When set, files live in this child folder under "[GOOGLE_DRIVE_FOLDER_NAME]" (e.g. "Strategies"),
    // isolating a namespace (like the strategy library) from the top-level DB archives.
    private val subfolderName: String? = null,
) : RemoteStorageProvider {
    private val httpClient: HttpClient = tokenSource.httpClient
    private var cachedFolderId: String? = null

    override suspend fun isSignedIn(): Boolean = tokenSource.isSignedIn()

    override suspend fun signIn() = tokenSource.signIn()

    override suspend fun signOut() {
        cachedFolderId = null
        tokenSource.signOut()
    }

    override suspend fun list(): List<RemoteFile> =
        withContext(ioDispatcher) {
            val driveQuery = "'${folderId()}' in parents and trashed = false"
            val body =
                httpClient
                    .get(FILES_ENDPOINT) {
                        url {
                            parameters.append("q", driveQuery)
                            parameters.append("spaces", "drive")
                            parameters.append("fields", "files($FILE_FIELDS)")
                        }
                    }.requireBody("list Google Drive files")
            json
                .decodeFromString<DriveFileList>(body)
                .files
                .preferOwnFiles()
                .map { it.toRemoteFile() }
        }

    override suspend fun stat(fileId: String): RemoteFile? =
        withContext(ioDispatcher) {
            val response =
                httpClient.get("$FILES_ENDPOINT/$fileId") {
                    url { parameters.append("fields", FILE_FIELDS) }
                }
            if (!response.status.isSuccess()) return@withContext null
            json.decodeFromString<DriveFile>(response.bodyAsText()).toRemoteFile()
        }

    override suspend fun download(fileId: String): ByteArray =
        withContext(ioDispatcher) {
            val response =
                httpClient.get("$FILES_ENDPOINT/$fileId") {
                    url { parameters.append("alt", "media") }
                }
            if (!response.status.isSuccess()) {
                throw RemoteStorageException("Failed to download $fileId from Google Drive (${response.status.value})")
            }
            response.body<ByteArray>()
        }

    override suspend fun upload(
        fileId: String?,
        name: String,
        bytes: ByteArray,
    ): RemoteFile =
        withContext(ioDispatcher) {
            val boundary = "mmboundary_${name.hashCode().toUInt()}_${bytes.size}"
            // Updating in place re-sends only the name; appProperties/parents are set once at create.
            val metadata =
                if (fileId == null) {
                    DriveFileMetadata(name = name, parents = listOf(folderId()), appProperties = APP_PROPERTIES)
                } else {
                    DriveFileMetadata(name = name)
                }
            val body = multipartRelated(boundary, json.encodeToString(metadata), bytes)
            val endpoint = if (fileId == null) UPLOAD_ENDPOINT else "$UPLOAD_ENDPOINT/$fileId"
            val configure: HttpRequestBuilder.() -> Unit = {
                url {
                    parameters.append("uploadType", "multipart")
                    parameters.append("fields", FILE_FIELDS)
                }
                multipartBody(boundary, body)
            }
            val response =
                if (fileId == null) httpClient.post(endpoint, configure) else httpClient.patch(endpoint, configure)
            // drive.file only lets the app write files it created, so a same-named file another app (or a person)
            // put in our folder is readable but not updatable. Write our own copy instead; list() prefers it.
            if (fileId != null && response.driveErrorReason() == APP_NOT_AUTHORIZED_TO_FILE) {
                return@withContext upload(fileId = null, name = name, bytes = bytes)
            }
            json.decodeFromString<DriveFile>(response.requireBody("upload to Google Drive")).toRemoteFile()
        }

    override suspend fun delete(fileId: String) {
        withContext(ioDispatcher) {
            val response = httpClient.delete("$FILES_ENDPOINT/$fileId")
            if (!response.status.isSuccess() && response.status.value != HTTP_NOT_FOUND) {
                throw RemoteStorageException("Failed to delete $fileId from Google Drive (${response.status.value})")
            }
        }
    }

    override suspend fun accessTokenExpiresAtEpochMs(): Long? = tokenSource.accessTokenExpiresAtEpochMs()

    /**
     * Finds (or creates on first use) the folder files live in: the app's "Money Manager" folder, or,
     * when [subfolderName] is set, that child folder under it. Returns its id (cached for the session).
     */
    private suspend fun folderId(): String {
        cachedFolderId?.let { return it }
        val base = findOrCreateFolder(GOOGLE_DRIVE_FOLDER_NAME, parentId = null)
        val target = if (subfolderName == null) base else findOrCreateFolder(subfolderName, parentId = base)
        cachedFolderId = target
        return target
    }

    private suspend fun findOrCreateFolder(
        name: String,
        parentId: String?,
    ): String {
        val parentClause = if (parentId != null) " and '$parentId' in parents" else ""
        val driveQuery = "mimeType = '$FOLDER_MIME_TYPE' and name = '$name' and trashed = false$parentClause"
        val body =
            httpClient
                .get(FILES_ENDPOINT) {
                    url {
                        parameters.append("q", driveQuery)
                        parameters.append("spaces", "drive")
                        parameters.append("fields", "files(id)")
                    }
                }.requireBody("find Drive folder")
        return json
            .decodeFromString<DriveFileList>(body)
            .files
            .firstOrNull()
            ?.id ?: createFolder(name, parentId)
    }

    private suspend fun createFolder(
        name: String,
        parentId: String?,
    ): String {
        val metadata =
            DriveFileMetadata(
                name = name,
                mimeType = FOLDER_MIME_TYPE,
                parents = parentId?.let { listOf(it) },
                appProperties = APP_PROPERTIES,
            )
        val response =
            httpClient.post(FILES_ENDPOINT) {
                url { parameters.append("fields", "id") }
                contentType(Json)
                setBody(json.encodeToString(metadata))
            }
        return json.decodeFromString<DriveFile>(response.requireBody("create Drive folder")).id
    }

    private suspend fun HttpResponse.requireBody(action: String): String {
        if (!status.isSuccess()) throw RemoteStorageException("Failed to $action (${status.value}${driveErrorSuffix()})")
        return bodyAsText()
    }

    private suspend fun HttpResponse.driveError(): DriveError? =
        if (status.isSuccess()) null else runCatching { json.decodeFromString<DriveErrorResponse>(bodyAsText()).error }.getOrNull()

    private suspend fun HttpResponse.driveErrorReason(): String? = driveError()?.errors?.firstOrNull()?.reason

    // Drive's status alone can't tell a permission refusal from a rate limit (both 403), so carry its reason.
    private suspend fun HttpResponse.driveErrorSuffix(): String {
        val error = driveError() ?: return ""
        val reason = error.errors.firstOrNull()?.reason
        val detail = listOfNotNull(reason, error.message).joinToString(": ")
        return if (detail.isEmpty()) "" else ", $detail"
    }

    /**
     * Of several same-named files, keeps only the ones this app created (tagged with [APP_PROPERTIES]) when there
     * are any: the others are another app's copies, which drive.file lets us read but never update.
     */
    private fun List<DriveFile>.preferOwnFiles(): List<DriveFile> {
        val ownNames = filter { it.isOwn() }.mapTo(mutableSetOf()) { it.name }
        return filter { it.isOwn() || it.name !in ownNames }
    }

    private fun DriveFile.isOwn() = appProperties?.get(APP_PROPERTY_KEY) == APP_PROPERTIES.getValue(APP_PROPERTY_KEY)

    private fun DriveFile.toRemoteFile() =
        RemoteFile(
            id = id,
            name = name,
            sizeBytes = size?.toLongOrNull(),
            revisionId = headRevisionId,
        )

    @Serializable
    private data class DriveFile(
        val id: String,
        val name: String = "",
        val size: String? = null,
        val headRevisionId: String? = null,
        val appProperties: Map<String, String>? = null,
    )

    @Serializable
    private data class DriveFileList(
        val files: List<DriveFile> = emptyList(),
    )

    @Serializable
    private data class DriveErrorResponse(
        val error: DriveError,
    )

    @Serializable
    private data class DriveError(
        val message: String? = null,
        val errors: List<DriveErrorDetail> = emptyList(),
    )

    @Serializable
    private data class DriveErrorDetail(
        val reason: String? = null,
    )

    /** Drive file metadata sent on create/update; null fields are omitted (encodeDefaults stays off). */
    @Serializable
    private data class DriveFileMetadata(
        val name: String,
        val mimeType: String? = null,
        val parents: List<String>? = null,
        val appProperties: Map<String, String>? = null,
    )

    private companion object {
        const val FILES_ENDPOINT = "https://www.googleapis.com/drive/v3/files"
        const val UPLOAD_ENDPOINT = "https://www.googleapis.com/upload/drive/v3/files"
        const val APP_PROPERTY_KEY = "moneymanagerDb"
        const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
        const val FILE_FIELDS = "id,name,size,headRevisionId,appProperties"
        const val APP_NOT_AUTHORIZED_TO_FILE = "appNotAuthorizedToFile"
        const val HTTP_NOT_FOUND = 404
        val APP_PROPERTIES = mapOf(APP_PROPERTY_KEY to "true")
        val json: JsonFormat = JsonFormat { ignoreUnknownKeys = true }

        fun HttpRequestBuilder.multipartBody(
            boundary: String,
            body: ByteArray,
        ) {
            // Drive's metadata upload needs multipart/related (not Ktor's MultiPartFormDataContent, which
            // is form-data), so the parts are assembled by hand but the content type uses Ktor's constant.
            contentType(ContentType.MultiPart.Related.withParameter("boundary", boundary))
            setBody(body)
        }
    }
}

/** Builds a `multipart/related` body: a JSON metadata part followed by the raw media part. */
internal fun multipartRelated(
    boundary: String,
    metadataJson: String,
    media: ByteArray,
): ByteArray {
    // MIME requires CRLF line endings, so they stay explicit (a raw string would emit LF and
    // corrupt the body). A local writer keeps the parts readable.
    val jsonPart = Json.withCharset(Charsets.UTF_8)
    val mediaPart = OctetStream
    return Buffer()
        .apply {
            val line = { text: String -> writeString("$text\r\n") }
            line("--$boundary")
            line("${HttpHeaders.ContentType}: $jsonPart")
            line("")
            line(metadataJson)
            line("--$boundary")
            line("${HttpHeaders.ContentType}: $mediaPart")
            line("")
            write(media)
            line("")
            line("--$boundary--")
        }.readByteArray()
}
