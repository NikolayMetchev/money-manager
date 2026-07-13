package com.moneymanager.remotestorage.googledrive

import com.moneymanager.remotestorage.RemoteAuthException
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * JVM/desktop [GoogleAccessTokenSource]: the installed-app loopback OAuth flow with the user's own
 * ("bring your own") OAuth client, persisting a refresh token via [GoogleDriveAccountStore]. The Drive
 * [httpClient] is the Bearer-plugin client built by the factory, which attaches the access token and
 * silently refreshes it (via [oauth] + the stored refresh token) on a 401.
 *
 * This is the original [GoogleDriveProvider] sign-in logic, moved behind the token-source seam so the
 * JVM behavior is unchanged while Android can swap in a native implementation.
 */
class JvmGoogleAccessTokenSource(
    private val credentials: GoogleDriveCredentials,
    private val accountStore: GoogleDriveAccountStore,
    private val browser: BrowserLauncher,
    private val oauth: GoogleOAuth,
    override val httpClient: HttpClient,
    private val scopes: List<String> = listOf(DRIVE_FILE_SCOPE),
) : GoogleAccessTokenSource {
    override suspend fun isSignedIn(): Boolean = accountStore.refreshToken(credentials.clientId) != null

    override suspend fun isSignedInWithRequiredScopes(): Boolean =
        isSignedIn() && accountStore.grantedScopes(credentials.clientId).containsAll(scopes)

    override suspend fun signIn() {
        performLoopbackSignIn(credentials, accountStore, browser, oauth, scopes)
    }

    override suspend fun signOut() {
        accountStore.clear(credentials.clientId)
    }

    override suspend fun accessToken(): String? = accountStore.accessToken(credentials.clientId)?.token

    override suspend fun accessTokenExpiresAtEpochMs(): Long? = accountStore.accessToken(credentials.clientId)?.expiresAtMillis
}

/**
 * The installed-app loopback consent flow: opens the browser, waits for the redirect, exchanges the
 * code, and persists the refresh/access tokens and granted scopes. Shared between the explicit
 * [JvmGoogleAccessTokenSource.signIn] and the Bearer client's expired-refresh-token fallback, so a
 * dead refresh token re-runs the same consent instead of surfacing an error. Returns the new tokens.
 */
internal suspend fun performLoopbackSignIn(
    credentials: GoogleDriveCredentials,
    accountStore: GoogleDriveAccountStore,
    browser: BrowserLauncher,
    oauth: GoogleOAuth,
    scopes: List<String>,
): GoogleTokens =
    withContext(Dispatchers.IO) {
        LoopbackRedirectReceiver().use { receiver ->
            val state = oauth.newState()
            browser.open(oauth.consentUrl(credentials.clientId, receiver.redirectUri, state, scopes))
            val code = receiver.awaitCode(state)
            val tokens = oauth.exchangeCode(credentials, code, receiver.redirectUri)
            val refreshToken =
                tokens.refreshToken
                    ?: throw RemoteAuthException(
                        "Google did not return a refresh token. Remove Money Manager from your Google " +
                            "account's third-party access and connect again.",
                    )
            accountStore.saveRefreshToken(credentials.clientId, refreshToken)
            accountStore.saveAccessToken(credentials.clientId, tokens.accessToken, accessTokenExpiry(tokens.expiresInSeconds))
            accountStore.saveGrantedScopes(credentials.clientId, scopes.toSet())
            tokens
        }
    }
