package com.moneymanager.remotestorage.googledrive

import com.moneymanager.localsettings.LocalSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer

// Plain client (no auth) for the OAuth token/consent endpoints — kept separate so the Bearer plugin's
// refreshTokens call below doesn't recurse through itself.
private val tokenHttpClient by lazy { HttpClient(CIO) }

// One Bearer-authenticated Drive client per OAuth client id, so each Google account refreshes its own
// token. Cached for the app lifetime (bounded by the number of accounts) to avoid per-call client churn.
private val driveClients = mutableMapOf<String, HttpClient>()

/** Builds a [GoogleDriveProvider] over a platform [tokenSource] (Android's native auth path). */
fun googleDriveProvider(tokenSource: GoogleAccessTokenSource): GoogleDriveProvider = GoogleDriveProvider(tokenSource)

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
    HttpClient(CIO) {
        install(Auth) {
            bearer {
                loadTokens { loadToken()?.let { BearerTokens(it, "") } }
                refreshTokens { refreshToken()?.let { BearerTokens(it, "") } }
                sendWithoutRequest { request -> request.url.host.endsWith("googleapis.com") }
            }
        }
    }

/**
 * Builds a [GoogleDriveProvider] for the JVM/desktop loopback flow from the OAuth client id/secret in
 * [config] (the app's shipped default credentials, or a per-binding override) plus the platform
 * [browser] for the consent step. Tokens are read from / written to [localSettings] via
 * [GoogleDriveAccountStore]; the Drive REST client uses Ktor's Bearer auth plugin to attach the access
 * token and refresh it on a 401.
 */
fun googleDriveProvider(
    config: String?,
    localSettings: LocalSettings,
    browser: BrowserLauncher,
): GoogleDriveProvider {
    val credentials =
        GoogleDriveCredentials.fromConfig(
            requireNotNull(config) { "Google Drive is not configured in this build (no OAuth client credentials)." },
        )
    val accountStore = GoogleDriveAccountStore(localSettings)
    val oauth = GoogleOAuth(tokenHttpClient)
    val driveClient =
        synchronized(driveClients) {
            driveClients.getOrPut(credentials.clientId) { buildDriveClient(credentials, accountStore, oauth) }
        }
    return GoogleDriveProvider(JvmGoogleAccessTokenSource(credentials, accountStore, browser, oauth, driveClient))
}

private fun buildDriveClient(
    credentials: GoogleDriveCredentials,
    accountStore: GoogleDriveAccountStore,
    oauth: GoogleOAuth,
): HttpClient =
    HttpClient(CIO) {
        install(Auth) {
            bearer {
                loadTokens {
                    accountStore.refreshToken(credentials.clientId)?.let { refresh ->
                        BearerTokens(accountStore.accessToken(credentials.clientId)?.token.orEmpty(), refresh)
                    }
                }
                refreshTokens {
                    accountStore.refreshToken(credentials.clientId)?.let { refresh ->
                        val tokens = oauth.refresh(credentials, refresh)
                        accountStore.saveAccessToken(
                            credentials.clientId,
                            tokens.accessToken,
                            accessTokenExpiry(tokens.expiresInSeconds),
                        )
                        BearerTokens(tokens.accessToken, refresh)
                    }
                }
                // Attach the token up front for Drive API hosts instead of waiting for a 401 challenge.
                sendWithoutRequest { request -> request.url.host.endsWith("googleapis.com") }
            }
        }
    }

/** Epoch-millis expiry from an OAuth `expires_in` (seconds); shared with sign-in so both agree. */
internal fun accessTokenExpiry(expiresInSeconds: Long): Long = System.currentTimeMillis() + expiresInSeconds * 1000L
