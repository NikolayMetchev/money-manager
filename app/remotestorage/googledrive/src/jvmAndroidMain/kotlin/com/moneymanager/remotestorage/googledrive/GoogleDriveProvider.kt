package com.moneymanager.remotestorage.googledrive

import com.moneymanager.remotestorage.RemoteAuthException
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
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import io.ktor.http.withCharset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import kotlin.time.Instant

/**
 * A [RemoteStorageProvider] backed by Google Drive, spoken directly over the Drive REST API v3 with the
 * shared Ktor client so it runs identically on JVM and Android (the Google Java SDK's OAuth is JVM-only).
 *
 * Bring-your-own-credentials: [credentials] is the user's own OAuth client (from their downloaded
 * `credentials.json`). [signIn] runs the installed-app loopback flow — open the consent page via
 * [browser], capture the code on [LoopbackRedirectReceiver], exchange it for tokens — and persists them
 * in [accountStore]. After that, sessions are silent: [httpClient] has Ktor's Bearer auth plugin
 * installed, which attaches the access token and, on a 401, refreshes it (via [oauth] + the stored
 * refresh token) and retries. Only a gone/revoked refresh token needs interactive re-authentication.
 *
 * Archives live in a "[GOOGLE_DRIVE_FOLDER_NAME]" folder (created on first use). Least privilege: only
 * [DRIVE_FILE_SCOPE]; uploads are tagged with an appProperty, and updates happen in place so the remote
 * file id stays stable across syncs.
 */
class GoogleDriveProvider(
    private val credentials: GoogleDriveCredentials,
    private val accountStore: GoogleDriveAccountStore,
    private val browser: BrowserLauncher,
    private val oauth: GoogleOAuth,
    private val httpClient: HttpClient,
    override val id: String = GOOGLE_DRIVE_PROVIDER_ID,
    override val displayName: String = "Google Drive",
) : RemoteStorageProvider {
    private var cachedFolderId: String? = null

    override suspend fun isSignedIn(): Boolean = accountStore.refreshToken(credentials.clientId) != null

    override suspend fun signIn() {
        withContext(Dispatchers.IO) {
            LoopbackRedirectReceiver().use { receiver ->
                browser.open(oauth.consentUrl(credentials.clientId, receiver.redirectUri))
                val code = receiver.awaitCode()
                val tokens = oauth.exchangeCode(credentials, code, receiver.redirectUri)
                val refreshToken =
                    tokens.refreshToken
                        ?: throw RemoteAuthException(
                            "Google did not return a refresh token. Remove Money Manager from your Google " +
                                "account's third-party access and connect again.",
                        )
                accountStore.saveRefreshToken(credentials.clientId, refreshToken)
                accountStore.saveAccessToken(credentials.clientId, tokens.accessToken, accessTokenExpiry(tokens.expiresInSeconds))
                // No need to reset the Bearer plugin's cache: sign-in itself makes no Drive request, so the
                // plugin first loads tokens (from the store) only on the next API call, after this persist.
            }
        }
    }

    override suspend fun signOut() {
        cachedFolderId = null
        accountStore.clear(credentials.clientId)
    }

    override suspend fun list(): List<RemoteFile> =
        withContext(Dispatchers.IO) {
            val query = "'${folderId()}' in parents and trashed = false"
            val url =
                "$FILES_ENDPOINT?q=${query.encodeURLParameter()}&spaces=drive" +
                    "&fields=${"files($FILE_FIELDS)".encodeURLParameter()}"
            val body = httpClient.get(url).requireBody("list Google Drive files")
            json.decodeFromString<DriveFileList>(body).files.map { it.toRemoteFile() }
        }

    override suspend fun stat(fileId: String): RemoteFile? =
        withContext(Dispatchers.IO) {
            val response = httpClient.get("$FILES_ENDPOINT/$fileId?fields=${FILE_FIELDS.encodeURLParameter()}")
            if (!response.status.isSuccess()) return@withContext null
            json.decodeFromString<DriveFile>(response.bodyAsText()).toRemoteFile()
        }

    override suspend fun download(fileId: String): ByteArray =
        withContext(Dispatchers.IO) {
            val response = httpClient.get("$FILES_ENDPOINT/$fileId?alt=media")
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
        withContext(Dispatchers.IO) {
            val boundary = "mmboundary_${name.hashCode().toUInt()}_${bytes.size}"
            // Updating in place re-sends only the name; appProperties/parents are set once at create.
            val metadata =
                if (fileId == null) {
                    DriveFileMetadata(name = name, parents = listOf(folderId()), appProperties = APP_PROPERTIES)
                } else {
                    DriveFileMetadata(name = name)
                }
            val body = multipartRelated(boundary, json.encodeToString(metadata), bytes)
            val fields = FILE_FIELDS.encodeURLParameter()
            val url =
                if (fileId == null) {
                    "$UPLOAD_ENDPOINT?uploadType=multipart&fields=$fields"
                } else {
                    "$UPLOAD_ENDPOINT/$fileId?uploadType=multipart&fields=$fields"
                }
            val response =
                if (fileId == null) {
                    httpClient.post(url) { multipartBody(boundary, body) }
                } else {
                    httpClient.patch(url) { multipartBody(boundary, body) }
                }
            json.decodeFromString<DriveFile>(response.requireBody("upload to Google Drive")).toRemoteFile()
        }

    override suspend fun delete(fileId: String) {
        withContext(Dispatchers.IO) {
            val response = httpClient.delete("$FILES_ENDPOINT/$fileId")
            if (!response.status.isSuccess() && response.status.value != HTTP_NOT_FOUND) {
                throw RemoteStorageException("Failed to delete $fileId from Google Drive (${response.status.value})")
            }
        }
    }

    override suspend fun accessTokenExpiresAtEpochMs(): Long? = accountStore.accessToken(credentials.clientId)?.expiresAtMillis

    /** Finds (or creates on first use) the app's "Money Manager" Drive folder and returns its id. */
    private suspend fun folderId(): String {
        cachedFolderId?.let { return it }
        val query = "mimeType = '$FOLDER_MIME_TYPE' and name = '$GOOGLE_DRIVE_FOLDER_NAME' and trashed = false"
        val url = "$FILES_ENDPOINT?q=${query.encodeURLParameter()}&spaces=drive&fields=${"files(id)".encodeURLParameter()}"
        val body = httpClient.get(url).requireBody("find Drive folder")
        val id =
            json
                .decodeFromString<DriveFileList>(body)
                .files
                .firstOrNull()
                ?.id ?: createFolder()
        cachedFolderId = id
        return id
    }

    private suspend fun createFolder(): String {
        val metadata =
            DriveFileMetadata(name = GOOGLE_DRIVE_FOLDER_NAME, mimeType = FOLDER_MIME_TYPE, appProperties = APP_PROPERTIES)
        val response =
            httpClient.post("$FILES_ENDPOINT?fields=id") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(metadata))
            }
        return json.decodeFromString<DriveFile>(response.requireBody("create Drive folder")).id
    }

    private suspend fun HttpResponse.requireBody(action: String): String {
        if (!status.isSuccess()) throw RemoteStorageException("Failed to $action (${status.value})")
        return bodyAsText()
    }

    private fun DriveFile.toRemoteFile() =
        RemoteFile(
            id = id,
            name = name,
            sizeBytes = size?.toLongOrNull(),
            modifiedAtEpochMs = modifiedTime?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() },
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
        const val FILE_FIELDS = "id,name,size,modifiedTime"
        const val HTTP_NOT_FOUND = 404
        val APP_PROPERTIES = mapOf(APP_PROPERTY_KEY to "true")
        val json = Json { ignoreUnknownKeys = true }

        fun HttpRequestBuilder.multipartBody(
            boundary: String,
            body: ByteArray,
        ) {
            // Drive's metadata upload needs multipart/related (not Ktor's MultiPartFormDataContent, which
            // is form-data), so the parts are assembled by hand but the content type uses Ktor's constant.
            contentType(ContentType.MultiPart.Related.withParameter("boundary", boundary))
            setBody(body)
        }

        /** Builds a `multipart/related` body: a JSON metadata part followed by the raw media part. */
        fun multipartRelated(
            boundary: String,
            metadataJson: String,
            media: ByteArray,
        ): ByteArray {
            val jsonPart = ContentType.Application.Json.withCharset(Charsets.UTF_8)
            val mediaPart = ContentType.Application.OctetStream
            return ByteArrayOutputStream()
                .apply {
                    write("--$boundary\r\nContent-Type: $jsonPart\r\n\r\n".toByteArray())
                    write(metadataJson.toByteArray())
                    write("\r\n--$boundary\r\nContent-Type: $mediaPart\r\n\r\n".toByteArray())
                    write(media)
                    write("\r\n--$boundary--\r\n".toByteArray())
                }.toByteArray()
        }
    }
}
