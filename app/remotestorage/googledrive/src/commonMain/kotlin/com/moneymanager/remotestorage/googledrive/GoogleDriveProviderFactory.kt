package com.moneymanager.remotestorage.googledrive

import com.moneymanager.httpclient.platformHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import kotlin.time.Clock

/** Builds a [GoogleDriveProvider] over a platform [tokenSource] (Android's native auth path). */
fun googleDriveProvider(
    tokenSource: GoogleAccessTokenSource,
    subfolder: String? = null,
): GoogleDriveProvider = GoogleDriveProvider(tokenSource, subfolderName = subfolder)

/**
 * A Drive REST [HttpClient] that attaches a bearer token from [loadToken] and, on a 401, re-fetches via
 * [refreshToken]. Keeps the Ktor engine + Auth plugin wiring inside this module so platform token
 * sources (e.g. the Android `AuthorizationClient` one) don't need Ktor on their classpath. The bearer
 * "refresh token" is unused here (Android has none); refreshes go back through [refreshToken].
 */
fun buildBearerDriveClient(
    loadToken: suspend () -> String?,
    refreshToken: suspend () -> String?,
): HttpClient =
    platformHttpClient {
        install(Auth) {
            bearer {
                loadTokens { loadToken()?.let { BearerTokens(it, "") } }
                refreshTokens { refreshToken()?.let { BearerTokens(it, "") } }
                sendWithoutRequest { request -> request.url.host.endsWith("googleapis.com") }
            }
        }
    }

/** Builds a [DriveImportFileSource] over a [tokenSource] for the folder identified by [folderId]. */
fun driveImportFileSource(
    tokenSource: GoogleAccessTokenSource,
    folderId: String,
): DriveImportFileSource = DriveImportFileSource(tokenSource, folderId)

/** Epoch-millis expiry from an OAuth `expires_in` (seconds); shared with sign-in so both agree. */
fun accessTokenExpiry(expiresInSeconds: Long): Long = nowEpochMillis() + expiresInSeconds * 1000L

private fun nowEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()
