package com.moneymanager.remotestorage.googledrive

import com.moneymanager.localsettings.LocalSettings

/** A cached OAuth access token and the epoch-millis instant it expires. */
data class StoredAccessToken(
    val token: String,
    val expiresAtMillis: Long,
)

/**
 * Persists Google OAuth tokens, keyed by the user's OAuth client id, in [LocalSettings] (outside any
 * money-manager database, which is itself ephemeral when cloud-backed). Two things are stored:
 *
 * - the long-lived **refresh token** — written once at interactive sign-in and reused indefinitely;
 * - the short-lived **access token** (+ its expiry) — so a freshly constructed provider, or a new app
 *   run, reuses a still-valid token instead of calling the token endpoint again. Only when it has
 *   expired (or a request is rejected) do we refresh; only if the refresh token itself is rejected do
 *   we fall back to interactive re-authentication.
 *
 * One token set per OAuth client means re-using the same client across databases shares the Google
 * account, while different clients (e.g. a second account) get their own tokens. Stored unencrypted
 * on-device, matching the existing posture for API session tokens.
 */
class GoogleDriveAccountStore(
    private val localSettings: LocalSettings,
) {
    fun refreshToken(clientId: String): String? = localSettings.getString(refreshKey(clientId))

    fun saveRefreshToken(
        clientId: String,
        refreshToken: String,
    ) {
        localSettings.putString(refreshKey(clientId), refreshToken)
    }

    fun accessToken(clientId: String): StoredAccessToken? {
        val token = localSettings.getString(accessKey(clientId)) ?: return null
        val expiry = localSettings.getString(expiryKey(clientId))?.toLongOrNull() ?: return null
        return StoredAccessToken(token, expiry)
    }

    fun saveAccessToken(
        clientId: String,
        token: String,
        expiresAtMillis: Long,
    ) {
        localSettings.putString(accessKey(clientId), token)
        localSettings.putString(expiryKey(clientId), expiresAtMillis.toString())
    }

    fun clearAccessToken(clientId: String) {
        localSettings.remove(accessKey(clientId))
        localSettings.remove(expiryKey(clientId))
    }

    /** The OAuth scopes most recently granted for [clientId] (empty if never recorded). */
    fun grantedScopes(clientId: String): Set<String> =
        localSettings
            .getString(scopesKey(clientId))
            ?.split(' ')
            ?.filter { it.isNotBlank() }
            ?.toSet()
            .orEmpty()

    fun saveGrantedScopes(
        clientId: String,
        scopes: Set<String>,
    ) {
        localSettings.putString(scopesKey(clientId), scopes.joinToString(" "))
    }

    fun clear(clientId: String) {
        localSettings.remove(refreshKey(clientId))
        localSettings.remove(scopesKey(clientId))
        clearAccessToken(clientId)
    }

    // Key by a short, stable hash of the client id, never the raw id: a Google OAuth client id is
    // ~72 chars, and the JVM LocalSettings backing (java.util.prefs) rejects keys over 80 chars — which
    // silently dropped the token, forcing a re-auth on every call. String.hashCode is deterministic
    // across runs/platforms, so the same client always maps to the same handle.
    private fun handle(clientId: String): String = clientId.hashCode().toUInt().toString(MAX_RADIX)

    private fun refreshKey(clientId: String) = "gdrive.rt.${handle(clientId)}"

    private fun accessKey(clientId: String) = "gdrive.at.${handle(clientId)}"

    private fun expiryKey(clientId: String) = "gdrive.ate.${handle(clientId)}"

    private fun scopesKey(clientId: String) = "gdrive.sc.${handle(clientId)}"

    private companion object {
        const val MAX_RADIX = 36
    }
}
