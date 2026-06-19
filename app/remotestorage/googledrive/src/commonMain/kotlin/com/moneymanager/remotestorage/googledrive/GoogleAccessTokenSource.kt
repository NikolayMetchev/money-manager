package com.moneymanager.remotestorage.googledrive

import io.ktor.client.HttpClient

/**
 * Supplies the OAuth access token for Google Drive REST calls, abstracting the platform sign-in
 * mechanism so [GoogleDriveProvider] stays platform-agnostic:
 * - **JVM/desktop**: the installed-app loopback flow + a refresh token persisted in `LocalSettings`.
 * - **Android**: Google Identity `AuthorizationClient` (a native consent sheet, short-lived tokens
 *   re-issued silently once the `drive.file` scope is granted — no refresh token, no backend).
 *
 * The source also owns the Drive REST [httpClient], pre-wired to attach this source's token. Putting
 * the client here breaks the otherwise-circular dependency (the provider needs the client; the
 * Android client needs the token), and lets each platform build the client the way its token model
 * requires (JVM keeps the Bearer-plugin-with-refresh client; Android attaches a silently-refreshed
 * token).
 */
interface GoogleAccessTokenSource {
    /** Drive REST client, pre-configured to attach this source's access token to googleapis.com calls. */
    val httpClient: HttpClient

    /** True when a usable session exists without prompting (a cached/silent grant). */
    suspend fun isSignedIn(): Boolean

    /** Runs interactive sign-in. Throws `RemoteAuthException` on cancellation or failure. */
    suspend fun signIn()

    /** Clears the cached session/credentials so a future call re-prompts. */
    suspend fun signOut()

    /** A currently-valid access token, acquired/refreshed silently when possible; null if not signed in. */
    suspend fun accessToken(): String?

    /** Epoch-millis expiry of the cached token for UI display, or null when unknown/not applicable. */
    suspend fun accessTokenExpiresAtEpochMs(): Long?
}
