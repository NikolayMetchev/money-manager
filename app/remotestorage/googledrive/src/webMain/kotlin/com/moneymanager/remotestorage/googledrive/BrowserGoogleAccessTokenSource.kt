@file:OptIn(ExperimentalWasmJsInterop::class)

package com.moneymanager.remotestorage.googledrive

import com.moneymanager.remotestorage.RemoteAuthException
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.time.Clock

/**
 * Google Drive sign-in in the browser, through Google Identity Services' token model: a popup grants an access token
 * for the "Web application" client [clientId], and there is no refresh token, so the vault keeps nothing. A lapsed
 * token is re-granted without a prompt where Google allows it; otherwise the person signs in again.
 *
 * Signing in asks Google for [GOOGLE_SIGN_IN_PROMPT], as the loopback flow does, so it shows the account chooser
 * rather than taking whichever account the browser is signed into; silent re-grants then name the chosen account, so
 * they can't switch to another one.
 *
 * The tokens live in memory only, so a reload signs out. Google's script loads when the source is built, so a
 * sign-in started by a click opens its popup while the click still counts as the person's gesture.
 */
class BrowserGoogleAccessTokenSource(
    private val clientId: String,
    private val scopes: List<String> = listOf(DRIVE_FILE_SCOPE),
) : GoogleAccessTokenSource {
    private val lock = Mutex()
    private var token: GrantedGoogleToken? = null
    private var accountEmail: String? = null

    init {
        loadGoogleIdentityServices()
    }

    override val httpClient: HttpClient =
        buildBearerDriveClient(
            loadToken = { accessToken() },
            refreshToken = {
                lock.withLock { token = null }
                accessToken()
            },
        )

    override suspend fun isSignedIn(): Boolean = usableToken() != null

    override suspend fun isSignedInWithRequiredScopes(): Boolean = usableToken()?.covers(scopes) == true

    override suspend fun signIn() {
        val granted = requestToken(prompt = GOOGLE_SIGN_IN_PROMPT, hint = null)
        lock.withLock {
            token = granted
            accountEmail = null
        }
        // Outside the lock: the Drive client takes it to read the token.
        val email = signedInEmail()
        lock.withLock { accountEmail = email }
    }

    override suspend fun signOut() {
        lock.withLock {
            token?.let { revokeGoogleToken(it.accessToken) }
            token = null
            accountEmail = null
        }
    }

    override suspend fun accessToken(): String? =
        lock.withLock {
            val current = token ?: return@withLock null
            if (current.isUsable(now())) return@withLock current.accessToken
            // Signed in earlier but lapsed: Google re-grants without a prompt while the person's session lasts.
            token = runCatching { requestToken(prompt = "none", hint = accountEmail) }.getOrNull()
            token?.accessToken
        }

    override suspend fun accessTokenExpiresAtEpochMs(): Long? = usableToken()?.expiresAtMillis

    private suspend fun usableToken(): GrantedGoogleToken? = lock.withLock { token?.takeIf { it.isUsable(now()) } }

    /** The chosen account's address, from Drive's `about` (readable with [DRIVE_FILE_SCOPE]); null if unavailable. */
    private suspend fun signedInEmail(): String? =
        runCatching {
            val response = httpClient.get(ABOUT_ENDPOINT) { url { parameters.append("fields", "user(emailAddress)") } }
            if (!response.status.isSuccess()) return@runCatching null
            json.decodeFromString<DriveAbout>(response.bodyAsText()).user?.emailAddress
        }.getOrNull()

    private suspend fun requestToken(
        prompt: String,
        hint: String?,
    ): GrantedGoogleToken =
        suspendCancellableCoroutine { continuation ->
            requestGoogleToken(
                clientId,
                scopes.joinToString(" "),
                prompt,
                hint.orEmpty(),
                onToken = { accessToken, expiresInSeconds, grantedScopes ->
                    continuation.resume(
                        GrantedGoogleToken(accessToken, accessTokenExpiry(expiresInSeconds.toLong()), grantedScopes),
                    )
                },
                onError = { error -> continuation.resumeWithException(RemoteAuthException("Google sign-in failed: $error")) },
            )
        }

    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    @Serializable
    private data class DriveAbout(
        val user: DriveUser? = null,
    )

    @Serializable
    private data class DriveUser(
        val emailAddress: String? = null,
    )

    private companion object {
        const val ABOUT_ENDPOINT = "https://www.googleapis.com/drive/v3/about"
        val json: Json = Json { ignoreUnknownKeys = true }
    }
}

private fun loadGoogleIdentityServices(): Unit =
    js(
        """{
        if (globalThis.__mmGoogleIdentity) return;
        globalThis.__mmGoogleIdentity = new Promise(function (resolve, reject) {
            if (globalThis.google && google.accounts && google.accounts.oauth2) { resolve(); return; }
            const script = document.createElement("script");
            script.src = "https://accounts.google.com/gsi/client";
            script.async = true;
            script.onload = function () { resolve(); };
            script.onerror = function () { reject(new Error("Google sign-in could not be loaded")); };
            document.head.appendChild(script);
        });
    }""",
    )

// The parameters are read by the JavaScript. Google answers through `callback` (a token, or an OAuth error) or, when
// the popup can't open or is closed, `error_callback`. An empty `hint` names no account.
@Suppress("UnusedParameter")
private fun requestGoogleToken(
    clientId: String,
    scope: String,
    prompt: String,
    hint: String,
    onToken: (String, Double, String) -> Unit,
    onError: (String) -> Unit,
): Unit =
    js(
        """{
        globalThis.__mmGoogleIdentity.then(function () {
            const client = google.accounts.oauth2.initTokenClient({
                client_id: clientId,
                scope: scope,
                hint: hint || undefined,
                callback: function (response) {
                    if (response.error) onError(response.error_description || response.error);
                    else onToken(response.access_token, Number(response.expires_in), response.scope || "");
                },
                error_callback: function (error) { onError(error.message || error.type || String(error)); }
            });
            client.requestAccessToken({ prompt: prompt });
        }, function (error) { onError(String(error.message || error)); });
    }""",
    )

@Suppress("UnusedParameter")
private fun revokeGoogleToken(accessToken: String): Unit =
    js(
        """{
        if (globalThis.google && google.accounts && google.accounts.oauth2) google.accounts.oauth2.revoke(accessToken, function () {});
    }""",
    )
