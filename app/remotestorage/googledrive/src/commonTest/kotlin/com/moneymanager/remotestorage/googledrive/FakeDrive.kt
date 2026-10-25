package com.moneymanager.remotestorage.googledrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * An in-memory Google Drive behind a Ktor [MockEngine]: just enough of `files.list` (the `q` terms the app sends,
 * paged by [pageSize]), folder create, multipart upload/update, `alt=media` download, metadata get and delete for
 * [GoogleDriveProvider] and [DriveImportFileSource]. [failures] answers a path with a status before anything else.
 */
class FakeDrive(
    private val pageSize: Int = Int.MAX_VALUE,
) {
    // A plain class: an upload updates an item in place, and a ByteArray has no value equality anyway.
    class Item(
        val id: String,
        var name: String,
        val parent: String?,
        val mimeType: String = "application/octet-stream",
        var bytes: ByteArray = ByteArray(0),
        val modifiedTime: String? = null,
        val md5Checksum: String? = null,
        var revision: Int = 1,
        val sharedWithMe: Boolean = false,
        val appProperties: Map<String, String> = emptyMap(),
        // Another app's file: drive.file lets this app read it, but an update is refused.
        val readOnly: Boolean = false,
    )

    val items = mutableListOf<Item>()
    val requests = mutableListOf<HttpRequestData>()
    val failures = mutableMapOf<String, HttpStatusCode>()
    private var nextId = 1

    val tokenSource: GoogleAccessTokenSource =
        object : GoogleAccessTokenSource {
            var signedIn = true
            override val httpClient = HttpClient(MockEngine { handle(it) })

            override suspend fun isSignedIn() = signedIn

            override suspend fun signIn() {
                signedIn = true
            }

            override suspend fun signOut() {
                signedIn = false
            }

            override suspend fun accessToken() = "token".takeIf { signedIn }

            override suspend fun accessTokenExpiresAtEpochMs() = 42L
        }

    fun add(
        name: String,
        parent: String?,
        mimeType: String = "application/octet-stream",
        bytes: ByteArray = ByteArray(0),
        modifiedTime: String? = null,
        md5Checksum: String? = null,
        sharedWithMe: Boolean = false,
        appProperties: Map<String, String> = emptyMap(),
        readOnly: Boolean = false,
    ): Item =
        Item(
            "id${nextId++}",
            name,
            parent,
            mimeType,
            bytes,
            modifiedTime,
            md5Checksum,
            sharedWithMe = sharedWithMe,
            appProperties = appProperties,
            readOnly = readOnly,
        ).also {
            items +=
                it
        }

    fun folder(
        name: String,
        parent: String? = null,
        sharedWithMe: Boolean = false,
    ): Item = add(name, parent, FOLDER, sharedWithMe = sharedWithMe)

    private fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        requests += request
        val path = request.url.encodedPath
        failures[path]?.let { return respond("""{"error":"$it"}""", it) }
        val fileId = path.substringAfter("/files", "").removePrefix("/").ifEmpty { null }
        val upload = path.startsWith("/upload/")
        return when (request.method) {
            HttpMethod.Get -> {
                if (fileId == null) list(request) else get(fileId, request)
            }

            HttpMethod.Post if !upload -> {
                createFolder(request)
            }

            HttpMethod.Post, HttpMethod.Patch -> {
                upload(fileId, request)
            }

            HttpMethod.Delete -> {
                if (items.removeAll { it.id == fileId }) respond("", HttpStatusCode.NoContent) else respond("", HttpStatusCode.NotFound)
            }

            else -> {
                respond("", HttpStatusCode.MethodNotAllowed)
            }
        }
    }

    private fun MockRequestHandleScope.list(request: HttpRequestData): HttpResponseData {
        val q = request.url.parameters["q"].orEmpty()
        val matching =
            items
                .filter { item ->
                    Regex("'([^']+)' in parents").findAll(q).all { item.parent == it.groupValues[1] } &&
                        Regex("name = '([^']+)'").find(q)?.let { item.name == it.groupValues[1] } ?: true &&
                        (!q.contains("mimeType = '$FOLDER'") || item.mimeType == FOLDER) &&
                        (!q.contains("mimeType != '$FOLDER'") || item.mimeType != FOLDER) &&
                        (!q.contains("sharedWithMe = true") || item.sharedWithMe)
                }.sortedBy { it.name }
        val start = request.url.parameters["pageToken"]?.toInt() ?: 0
        val page = matching.drop(start).take(pageSize)
        val next = (start + pageSize).takeIf { it < matching.size }
        return json(
            buildJsonObject {
                put("files", buildJsonArray { page.forEach { add(it.toJson()) } })
                next?.let { put("nextPageToken", it.toString()) }
            },
        )
    }

    private fun MockRequestHandleScope.get(
        fileId: String,
        request: HttpRequestData,
    ): HttpResponseData {
        val item = items.firstOrNull { it.id == fileId } ?: return respond("", HttpStatusCode.NotFound)
        return if (request.url.parameters["alt"] == "media") {
            respond(item.bytes, HttpStatusCode.OK)
        } else {
            json(item.toJson())
        }
    }

    private fun MockRequestHandleScope.createFolder(request: HttpRequestData): HttpResponseData {
        val metadata = Json.parseToJsonElement(request.bodyText()).jsonObject
        val item =
            Item(
                id = "id${nextId++}",
                name = metadata.getValue("name").jsonPrimitive.content,
                parent =
                    metadata["parents"]
                        ?.jsonArray
                        ?.single()
                        ?.jsonPrimitive
                        ?.content,
                mimeType = metadata.getValue("mimeType").jsonPrimitive.content,
                appProperties = metadata.appProperties(),
            )
        items += item
        return json(item.toJson())
    }

    private fun MockRequestHandleScope.upload(
        fileId: String?,
        request: HttpRequestData,
    ): HttpResponseData {
        // The provider builds multipart/related by hand: a JSON metadata part, then the media part.
        val boundary = request.body.contentType!!.parameter("boundary")!!
        val parts =
            request
                .bodyText()
                .split("--$boundary")
                .drop(1)
                .dropLast(1)
        val metadata = Json.parseToJsonElement(parts[0].substringAfter("\r\n\r\n").trim()).jsonObject
        val media = parts[1].substringAfter("\r\n\r\n").removeSuffix("\r\n").encodeToByteArray()
        val name = metadata.getValue("name").jsonPrimitive.content
        val item =
            if (fileId == null) {
                Item(
                    id = "id${nextId++}",
                    name = name,
                    parent =
                        metadata["parents"]
                            ?.jsonArray
                            ?.single()
                            ?.jsonPrimitive
                            ?.content,
                    bytes = media,
                    appProperties = metadata.appProperties(),
                ).also { items += it }
            } else {
                val existing = items.firstOrNull { it.id == fileId } ?: return respond("", HttpStatusCode.NotFound)
                if (existing.readOnly) {
                    return respond(
                        """{"error":{"code":403,"message":"No write access","errors":[{"reason":"appNotAuthorizedToFile"}]}}""",
                        HttpStatusCode.Forbidden,
                    )
                }
                existing.also {
                    it.name = name
                    it.bytes = media
                    it.revision++
                }
            }
        return json(item.toJson())
    }

    private fun JsonObject.appProperties() = this["appProperties"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()

    private fun HttpRequestData.bodyText() = (body as OutgoingContent.ByteArrayContent).bytes().decodeToString()

    private fun Item.toJson() =
        buildJsonObject {
            put("id", id)
            put("name", name)
            put("size", bytes.size.toString())
            put("headRevisionId", "rev$revision")
            modifiedTime?.let { put("modifiedTime", it) }
            md5Checksum?.let { put("md5Checksum", it) }
            if (appProperties.isNotEmpty()) {
                put("appProperties", buildJsonObject { appProperties.forEach { (key, value) -> put(key, value) } })
            }
        }

    private fun MockRequestHandleScope.json(body: JsonObject) =
        respond(body.toString(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))

    companion object {
        const val FOLDER = "application/vnd.google-apps.folder"
    }
}
