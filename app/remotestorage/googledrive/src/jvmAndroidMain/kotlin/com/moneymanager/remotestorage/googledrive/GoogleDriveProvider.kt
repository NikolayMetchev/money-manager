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
import io.ktor.http.HttpHeaders
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
 * refresh token in [accountStore] so later launches refresh silently.
 *
 * Least privilege: only [DRIVE_FILE_SCOPE]; uploads are tagged with an appProperty so [list] finds the
 * app's own archives, and updates happen in place so the remote file id stays stable across syncs.
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
                cachedToken = CachedToken(tokens.accessToken, expiryFromNow(tokens.expiresInSeconds))
            }
        }
    }

    override suspend fun signOut() {
        cachedToken = null
        accountStore.clear(credentials.clientId)
    }

    override suspend fun list(): List<RemoteFile> =
        withContext(Dispatchers.IO) {
            val query = "appProperties has { key='$APP_PROPERTY_KEY' and value='true' } and trashed = false"
            val url =
                "$FILES_ENDPOINT?q=${query.encodeURLParameter()}&spaces=drive" +
                    "&fields=${"files($FILE_FIELDS)".encodeURLParameter()}"
            val body = authorizedGet(url).requireBody("list Google Drive files")
            json.decodeFromString<DriveFileList>(body).files.map { it.toRemoteFile() }
        }

    override suspend fun stat(fileId: String): RemoteFile? =
        withContext(Dispatchers.IO) {
            val response = authorizedGet("$FILES_ENDPOINT/$fileId?fields=${FILE_FIELDS.encodeURLParameter()}")
            if (!response.status.isSuccess()) return@withContext null
            json.decodeFromString<DriveFile>(response.bodyAsText()).toRemoteFile()
        }

    override suspend fun download(fileId: String): ByteArray =
        withContext(Dispatchers.IO) {
            val response = httpClient.get("$FILES_ENDPOINT/$fileId?alt=media") { bearer(accessToken()) }
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
            val token = accessToken()
            val boundary = "mmboundary_${name.hashCode().toUInt()}_${bytes.size}"
            // Updating in place re-sends only the name; appProperties/parents are set once at create.
            val metadata =
                if (fileId == null) {
                    """{"name":${name.jsonQuoted()},"appProperties":{"$APP_PROPERTY_KEY":"true"}}"""
                } else {
                    """{"name":${name.jsonQuoted()}}"""
                }
            val body = multipartRelated(boundary, metadata, bytes)
            val fields = FILE_FIELDS.encodeURLParameter()
            val response =
                if (fileId == null) {
                    httpClient.post("$UPLOAD_ENDPOINT?uploadType=multipart&fields=$fields") {
                        uploadBody(token, boundary, body)
                    }
                } else {
                    httpClient.patch("$UPLOAD_ENDPOINT/$fileId?uploadType=multipart&fields=$fields") {
                        uploadBody(token, boundary, body)
                    }
                }
            json.decodeFromString<DriveFile>(response.requireBody("upload to Google Drive")).toRemoteFile()
        }

    override suspend fun delete(fileId: String) {
        withContext(Dispatchers.IO) {
            val response = httpClient.delete("$FILES_ENDPOINT/$fileId") { bearer(accessToken()) }
            if (!response.status.isSuccess() && response.status.value != HTTP_NOT_FOUND) {
                throw RemoteStorageException("Failed to delete $fileId from Google Drive (${response.status.value})")
            }
        }
    }

    private suspend fun authorizedGet(url: String): HttpResponse {
        val token = accessToken()
        return httpClient.get(url) { bearer(token) }
    }

    private suspend fun HttpResponse.requireBody(action: String): String {
        if (!status.isSuccess()) throw RemoteStorageException("Failed to $action (${status.value})")
        return bodyAsText()
    }

    /** Returns a valid access token, refreshing via the stored refresh token when the cache is stale. */
    private suspend fun accessToken(): String {
        cachedToken?.let { if (it.expiresAtMillis > nowMillis() + EXPIRY_SKEW_MILLIS) return it.token }
        val refreshToken =
            accountStore.refreshToken(credentials.clientId)
                ?: throw RemoteAuthException("Not signed in to Google Drive")
        val tokens = oauth.refresh(credentials, refreshToken)
        cachedToken = CachedToken(tokens.accessToken, expiryFromNow(tokens.expiresInSeconds))
        return tokens.accessToken
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
        val name: String,
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
            header(HttpHeaders.ContentType, "multipart/related; boundary=$boundary")
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
