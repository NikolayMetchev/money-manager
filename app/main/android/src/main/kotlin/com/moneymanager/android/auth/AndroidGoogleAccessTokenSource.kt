package com.moneymanager.android.auth

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.moneymanager.remotestorage.RemoteAuthException
import com.moneymanager.remotestorage.googledrive.DRIVE_FILE_SCOPE
import com.moneymanager.remotestorage.googledrive.GoogleAccessTokenSource
import com.moneymanager.remotestorage.googledrive.buildBearerDriveClient
import io.ktor.client.HttpClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Android [GoogleAccessTokenSource] backed by Google Identity Services' `AuthorizationClient`. Sign-in
 * shows a native account/consent sheet (no browser, no loopback) and GMS re-issues short-lived access
 * tokens silently once the `drive.file` scope is granted — so there is no refresh token and no backend.
 *
 * Requires Google Play Services and an Android-type OAuth client registered for this app's package +
 * signing SHA-1 in the Cloud Console (no client id is passed in code; GMS matches by package/signature).
 */
class AndroidGoogleAccessTokenSource(
    context: Context,
    private val consentLauncher: GoogleAuthConsentLauncher,
) : GoogleAccessTokenSource {
    private val authorizationClient = Identity.getAuthorizationClient(context)

    @Volatile
    private var cachedToken: String? = null

    // Drive REST client: attaches the current token, and on a 401 forces a fresh silent re-authorize.
    override val httpClient: HttpClient =
        buildBearerDriveClient(
            loadToken = { accessToken() },
            refreshToken = {
                cachedToken = null
                accessToken()
            },
        )

    override suspend fun isSignedIn(): Boolean = runCatching { authorizeSilently() != null }.getOrDefault(false)

    override suspend fun signIn() {
        cachedToken =
            authorizeInteractive().accessToken
                ?: throw RemoteAuthException("Google did not return an access token")
    }

    override suspend fun signOut() {
        // Clearing the cached token is enough; the database binding is removed by the caller, and a future
        // sign-in re-runs authorize(). (AuthorizationClient has no on-device "forget" beyond this.)
        cachedToken = null
    }

    override suspend fun accessToken(): String? = cachedToken ?: authorizeSilently()?.accessToken?.also { cachedToken = it }

    // GMS access tokens are opaque and refreshed silently by authorize(); no meaningful expiry to surface.
    override suspend fun accessTokenExpiresAtEpochMs(): Long? = null

    private fun request(): AuthorizationRequest =
        AuthorizationRequest
            .builder()
            .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
            .build()

    /** Returns a result only when a token is available without UI; null when consent would be required. */
    private suspend fun authorizeSilently(): AuthorizationResult? = awaitAuthorize().takeUnless { it.hasResolution() }

    /** Runs authorize(), launching the consent sheet when GMS requires user interaction. */
    private suspend fun authorizeInteractive(): AuthorizationResult {
        val result = awaitAuthorize()
        if (!result.hasResolution()) return result
        val pendingIntent =
            result.pendingIntent ?: throw RemoteAuthException("Google sign-in could not be started")
        val data =
            consentLauncher.launch(pendingIntent) ?: throw RemoteAuthException("Google sign-in was cancelled")
        return authorizationResultFromIntent(data)
    }

    private fun authorizationResultFromIntent(data: Intent): AuthorizationResult =
        runCatching { authorizationClient.getAuthorizationResultFromIntent(data) }
            .getOrElse { throw RemoteAuthException(it.message ?: "Google sign-in failed", it) }

    private suspend fun awaitAuthorize(): AuthorizationResult =
        suspendCancellableCoroutine { continuation ->
            authorizationClient
                .authorize(request())
                .addOnSuccessListener { continuation.resume(it) }
                .addOnFailureListener {
                    continuation.resumeWithException(RemoteAuthException(it.message ?: "Google authorization failed", it))
                }
        }
}
