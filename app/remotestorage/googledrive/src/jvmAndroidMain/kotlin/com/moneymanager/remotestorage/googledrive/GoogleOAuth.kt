package com.moneymanager.remotestorage.googledrive

import com.moneymanager.remotestorage.RemoteAuthException
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom

/** Result of a token endpoint call. [refreshToken] is only present on the initial authorization. */
data class GoogleTokens(
    val accessToken: String,
    val expiresInSeconds: Long,
    val refreshToken: String?,
)

/**
 * The Google OAuth 2.0 endpoints needed for the installed-app (loopback) flow with the user's own
 * client: build the consent URL, exchange an authorization code for tokens, and refresh an access
 * token. Pure HTTP over the shared Ktor client so it runs on JVM and Android alike.
 */
class GoogleOAuth(
    private val httpClient: HttpClient,
) {
    /** A fresh, unguessable `state` value to bind the consent request to its callback (anti-CSRF). */
    fun newState(): String {
        val bytes = ByteArray(STATE_BYTES)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    fun consentUrl(
        clientId: String,
        redirectUri: String,
        state: String,
        scopes: List<String> = listOf(DRIVE_FILE_SCOPE),
    ): String =
        URLBuilder(AUTH_ENDPOINT)
            .apply {
                parameters.append("client_id", clientId)
                parameters.append("redirect_uri", redirectUri)
                parameters.append("response_type", "code")
                parameters.append("scope", scopes.joinToString(" "))
                // offline + consent forces Google to return a refresh token we can reuse silently later.
                parameters.append("access_type", "offline")
                parameters.append("prompt", "consent")
                // state binds this request to the callback so a forged redirect can't inject a code (CSRF).
                parameters.append("state", state)
            }.buildString()

    suspend fun exchangeCode(
        credentials: GoogleDriveCredentials,
        code: String,
        redirectUri: String,
    ): GoogleTokens =
        tokenRequest(
            Parameters.build {
                append("code", code)
                append("client_id", credentials.clientId)
                append("client_secret", credentials.clientSecret)
                append("redirect_uri", redirectUri)
                append("grant_type", "authorization_code")
            },
        )

    suspend fun refresh(
        credentials: GoogleDriveCredentials,
        refreshToken: String,
    ): GoogleTokens =
        tokenRequest(
            Parameters.build {
                append("client_id", credentials.clientId)
                append("client_secret", credentials.clientSecret)
                append("refresh_token", refreshToken)
                append("grant_type", "refresh_token")
            },
        )

    private suspend fun tokenRequest(form: Parameters): GoogleTokens {
        val response = httpClient.submitForm(url = TOKEN_ENDPOINT, formParameters = form)
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw RemoteAuthException("Google token request failed (${response.status.value}): $body")
        }
        val parsed = json.decodeFromString<TokenResponse>(body)
        val accessToken =
            parsed.accessToken ?: throw RemoteAuthException("Google token response had no access token")
        return GoogleTokens(accessToken, parsed.expiresIn ?: 0L, parsed.refreshToken)
    }

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String? = null,
        @SerialName("expires_in") val expiresIn: Long? = null,
        @SerialName("refresh_token") val refreshToken: String? = null,
    )

    private companion object {
        const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
        const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
        const val STATE_BYTES = 16
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** Least-privilege scope: the app can only see and manage files it created. */
const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

/**
 * Read-only access to the user's Drive files. Required to list + download CSV/QIF files the user drops
 * into an arbitrary folder (those files were not created by the app, so [DRIVE_FILE_SCOPE] can't see
 * them). Only requested when configuring a Google Drive import directory.
 */
const val DRIVE_READONLY_SCOPE = "https://www.googleapis.com/auth/drive.readonly"
