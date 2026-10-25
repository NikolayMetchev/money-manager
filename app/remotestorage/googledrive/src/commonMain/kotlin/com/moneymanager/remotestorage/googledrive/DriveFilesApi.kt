package com.moneymanager.remotestorage.googledrive

import com.moneymanager.remotestorage.RemoteStorageException
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json as JsonFormat

internal const val DRIVE_FILES_ENDPOINT = "https://www.googleapis.com/drive/v3/files"

/** Drive's `errors[].reason` when drive.file lets the app read a file but not write it (another app made it). */
internal const val APP_NOT_AUTHORIZED_TO_FILE = "appNotAuthorizedToFile"

private const val PAGE_SIZE = "1000"
private const val MAX_RAW_ERROR_CHARS = 200
private val json = JsonFormat { ignoreUnknownKeys = true }

/** The response body, or a [RemoteStorageException] naming [action], the status and Drive's reason. */
internal suspend fun HttpResponse.requireDriveBody(action: String): String {
    if (!status.isSuccess()) throw RemoteStorageException("Failed to $action (${status.value}${driveErrorSuffix()})")
    return bodyAsText()
}

/** Drive's first `errors[].reason` for a failed response, or null. */
internal suspend fun HttpResponse.driveErrorReason(): String? = driveError()?.errors?.firstOrNull()?.reason

/**
 * Runs a Drive `files.list` query and follows `nextPageToken` to the end, accumulating every page's items via
 * [decodePage] (which returns that page's items + its next-page token). [fields] must select `nextPageToken`.
 * `includeItemsFromAllDrives`/`supportsAllDrives` surface Shared Drive content; not using `corpora=allDrives` (it
 * conflicts with the 'root' in parents / sharedWithMe terms).
 */
internal suspend fun <T> fetchAllDrivePages(
    client: HttpClient,
    driveQuery: String,
    fields: String,
    action: String,
    decodePage: (String) -> Pair<List<T>, String?>,
): List<T> {
    val all = mutableListOf<T>()
    var pageToken: String? = null
    do {
        val body =
            client
                .get(DRIVE_FILES_ENDPOINT) {
                    url {
                        parameters.append("q", driveQuery)
                        parameters.append("spaces", "drive")
                        parameters.append("fields", fields)
                        parameters.append("orderBy", "name")
                        parameters.append("pageSize", PAGE_SIZE)
                        parameters.append("includeItemsFromAllDrives", "true")
                        parameters.append("supportsAllDrives", "true")
                        pageToken?.let { parameters.append("pageToken", it) }
                    }
                }.requireDriveBody(action)
        val (items, next) = decodePage(body)
        all += items
        pageToken = next
    } while (pageToken != null)
    return all
}

private suspend fun HttpResponse.driveError(): DriveError? =
    if (status.isSuccess()) null else runCatching { json.decodeFromString<DriveErrorResponse>(bodyAsText()).error }.getOrNull()

// Drive's status alone can't tell a permission refusal from a rate limit (both 403), so carry its reason. A body
// that isn't Drive's error shape (a proxy's page, say) is kept as raw text instead.
private suspend fun HttpResponse.driveErrorSuffix(): String {
    val error = driveError()
    val detail =
        if (error != null) {
            listOfNotNull(error.errors.firstOrNull()?.reason, error.message).joinToString(": ")
        } else {
            runCatching { bodyAsText() }.getOrDefault("").trim().take(MAX_RAW_ERROR_CHARS)
        }
    return if (detail.isEmpty()) "" else ", $detail"
}

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
