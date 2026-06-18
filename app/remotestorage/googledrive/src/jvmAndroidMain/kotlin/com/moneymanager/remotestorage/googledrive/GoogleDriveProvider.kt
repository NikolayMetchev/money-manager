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
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import kotlin.time.Instant

/**
 * A [RemoteStorageProvider] backed by Google Drive, spoken directly over the Drive REST API v3 with the
 * shared Ktor client so it runs identically on JVM and Android (the Google Java SDK's OAuth is JVM-only).
 *
 * Bring-your-own-credentials: [credentials] is the user's own OAuth client (from their downloaded
 * `credentials.json`). [signIn] runs the installed-app loopback flow — open the consent page via
 * [browser], capture the code on [LoopbackRedirectReceiver], exchange it for tokens — and persists the
 * tokens in [accountStore]. After that, sessions are silent: a still-valid access token is reused from
 * the store, an expired one is refreshed, and a rejected one triggers a single forced refresh; only if
 * the refresh token itself is gone/revoked is interactive re-authentication needed.
 *
 * Archives live in a "[GOOGLE_DRIVE_FOLDER_NAME]" folder (created on first use). Least privilege: only
 * [DRIVE_FILE_SCOPE]; uploads are tagged with an appProperty, and updates happen in place so the remote
 * file id stays stable across syncs.
 */
class GoogleDriveProvider(
    private val credentials: GoogleDriveCredentials,
    private val accountStore: GoogleDriveAccountStore,
    private val browser: BrowserLauncher,
    private val httpClient: HttpClient,
    override val id: String = GOOGLE_DRIVE_PROVIDER_ID,
    override val displayName: String = "Google Drive",
) : RemoteStorageProvider {
    private val oauth = GoogleOAuth(httpClient)

    private data class CachedToken(val token: String, val expiresAtMillis: Long)

    private var cachedToken: CachedToken? = null
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
                storeAccessToken(tokens.accessToken, tokens.expiresInSeconds)
            }
        }
    }

    override suspend fun signOut() {
        cachedToken = null
        cachedFolderId = null
        accountStore.clear(credentials.clientId)
    }

    override suspend fun list(): List<RemoteFile> =
        withContext(Dispatchers.IO) {
            val query = "'${folderId()}' in parents and trashed = false"
            val url =
                "$FILES_ENDPOINT?q=${query.encodeURLParameter()}&spaces=drive" +
                    "&fields=${"files($FILE_FIELDS)".encodeURLParameter()}"
            val body = withAuthRetry { token -> httpClient.get(url) { bearer(token) } }.requireBody("list Google Drive files")
            json.decodeFromString<DriveFileList>(body).files.map { it.toRemoteFile() }
        }

    override suspend fun stat(fileId: String): RemoteFile? =
        withContext(Dispatchers.IO) {
            val url = "$FILES_ENDPOINT/$fileId?fields=${FILE_FIELDS.encodeURLParameter()}"
            val response = withAuthRetry { token -> httpClient.get(url) { bearer(token) } }
            if (!response.status.isSuccess()) return@withContext null
            json.decodeFromString<DriveFile>(response.bodyAsText()).toRemoteFile()
        }

    override suspend fun download(fileId: String): ByteArray =
        withContext(Dispatchers.IO) {
            val url = "$FILES_ENDPOINT/$fileId?alt=media"
            val response = withAuthRetry { token -> httpClient.get(url) { bearer(token) } }
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
                    """{"name":${name.jsonQuoted()},"parents":["${folderId()}"],""" +
                        """"appProperties":{"$APP_PROPERTY_KEY":"true"}}"""
                } else {
                    """{"name":${name.jsonQuoted()}}"""
                }
            val body = multipartRelated(boundary, metadata, bytes)
            val fields = FILE_FIELDS.encodeURLParameter()
            val response =
                withAuthRetry { token ->
                    if (fileId == null) {
                        httpClient.post("$UPLOAD_ENDPOINT?uploadType=multipart&fields=$fields") {
                            uploadBody(token, boundary, body)
                        }
                    } else {
                        httpClient.patch("$UPLOAD_ENDPOINT/$fileId?uploadType=multipart&fields=$fields") {
                            uploadBody(token, boundary, body)
                        }
                    }
                }
            json.decodeFromString<DriveFile>(response.requireBody("upload to Google Drive")).toRemoteFile()
        }

    override suspend fun delete(fileId: String) {
        withContext(Dispatchers.IO) {
            val response = withAuthRetry { token -> httpClient.delete("$FILES_ENDPOINT/$fileId") { bearer(token) } }
            if (!response.status.isSuccess() && response.status.value != HTTP_NOT_FOUND) {
                throw RemoteStorageException("Failed to delete $fileId from Google Drive (${response.status.value})")
            }
        }
    }

    override suspend fun accessTokenExpiresAtEpochMs(): Long? =
        cachedToken?.expiresAtMillis ?: accountStore.accessToken(credentials.clientId)?.expiresAtMillis

    /** Finds (or creates on first use) the app's "Money Manager" Drive folder and returns its id. */
    private suspend fun folderId(): String {
        cachedFolderId?.let { return it }
        val query =
            "mimeType = 'application/vnd.google-apps.folder' and name = '$GOOGLE_DRIVE_FOLDER_NAME' " +
                "and trashed = false"
        val url = "$FILES_ENDPOINT?q=${query.encodeURLParameter()}&spaces=drive&fields=${"files(id)".encodeURLParameter()}"
        val body = withAuthRetry { token -> httpClient.get(url) { bearer(token) } }.requireBody("find Drive folder")
        val id = json.decodeFromString<DriveFileList>(body).files.firstOrNull()?.id ?: createFolder()
        cachedFolderId = id
        return id
    }

    private suspend fun createFolder(): String {
        val metadata =
            """{"name":${GOOGLE_DRIVE_FOLDER_NAME.jsonQuoted()},""" +
                """"mimeType":"application/vnd.google-apps.folder",""" +
                """"appProperties":{"$APP_PROPERTY_KEY":"true"}}"""
        val response =
            withAuthRetry { token ->
                httpClient.post("$FILES_ENDPOINT?fields=id") {
                    bearer(token)
                    contentType(ContentType.Application.Json)
                    setBody(metadata)
                }
            }
        return json.decodeFromString<DriveFile>(response.requireBody("create Drive folder")).id
    }

    /**
     * Runs [request] with a valid access token; if Drive rejects it (401, e.g. revoked early), forces one
     * refresh and retries. A failed refresh surfaces as [RemoteAuthException] so the UI can reconnect.
     */
    private suspend fun withAuthRetry(request: suspend (token: String) -> HttpResponse): HttpResponse {
        val response = request(accessToken())
        if (response.status != HttpStatusCode.Unauthorized) return response
        cachedToken = null
        accountStore.clearAccessToken(credentials.clientId)
        return request(accessToken(forceRefresh = true))
    }

    private suspend fun HttpResponse.requireBody(action: String): String {
        if (!status.isSuccess()) throw RemoteStorageException("Failed to $action (${status.value})")
        return bodyAsText()
    }

    /** Returns a valid access token: reuse the cached/stored one until it expires, else refresh. */
    private suspend fun accessToken(forceRefresh: Boolean = false): String {
        if (!forceRefresh) {
            cachedToken?.let { if (it.expiresAtMillis > nowMillis() + EXPIRY_SKEW_MILLIS) return it.token }
            accountStore.accessToken(credentials.clientId)?.let { stored ->
                if (stored.expiresAtMillis > nowMillis() + EXPIRY_SKEW_MILLIS) {
                    cachedToken = CachedToken(stored.token, stored.expiresAtMillis)
                    return stored.token
                }
            }
        }
        val refreshToken =
            accountStore.refreshToken(credentials.clientId)
                ?: throw RemoteAuthException("Not signed in to Google Drive")
        val tokens = oauth.refresh(credentials, refreshToken)
        return storeAccessToken(tokens.accessToken, tokens.expiresInSeconds)
    }

    private fun storeAccessToken(
        token: String,
        expiresInSeconds: Long,
    ): String {
        val expiresAt = expiryFromNow(expiresInSeconds)
        cachedToken = CachedToken(token, expiresAt)
        accountStore.saveAccessToken(credentials.clientId, token, expiresAt)
        return token
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
    private data class DriveFileList(val files: List<DriveFile> = emptyList())

    private companion object {
        const val FILES_ENDPOINT = "https://www.googleapis.com/drive/v3/files"
        const val UPLOAD_ENDPOINT = "https://www.googleapis.com/upload/drive/v3/files"
        const val APP_PROPERTY_KEY = "moneymanagerDb"
        const val FILE_FIELDS = "id,name,size,modifiedTime"
        const val EXPIRY_SKEW_MILLIS = 60_000L
        const val HTTP_NOT_FOUND = 404
        val json = Json { ignoreUnknownKeys = true }

        fun nowMillis(): Long = System.currentTimeMillis()

        fun expiryFromNow(seconds: Long): Long = nowMillis() + seconds * 1000L

        fun HttpRequestBuilder.bearer(token: String) {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        fun HttpRequestBuilder.uploadBody(
            token: String,
            boundary: String,
            body: ByteArray,
        ) {
            bearer(token)
            contentType(ContentType("multipart", "related").withParameter("boundary", boundary))
            setBody(body)
        }

        /** Builds a `multipart/related` body: a JSON metadata part followed by the raw media part. */
        fun multipartRelated(
            boundary: String,
            metadataJson: String,
            media: ByteArray,
        ): ByteArray =
            ByteArrayOutputStream().apply {
                write("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n".toByteArray())
                write(metadataJson.toByteArray())
                write("\r\n--$boundary\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
                write(media)
                write("\r\n--$boundary--\r\n".toByteArray())
            }.toByteArray()

        fun String.jsonQuoted(): String = JsonPrimitive(this).toString()
    }
}
