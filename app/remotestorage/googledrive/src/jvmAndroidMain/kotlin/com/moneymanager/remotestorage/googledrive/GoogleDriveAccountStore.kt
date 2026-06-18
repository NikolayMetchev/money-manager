package com.moneymanager.remotestorage.googledrive

import com.moneymanager.localsettings.LocalSettings

/**
 * Persists the long-lived Google OAuth refresh token, keyed by the user's OAuth client id, in
 * [LocalSettings] (outside any money-manager database, which is itself ephemeral when cloud-backed).
 * One refresh token per OAuth client means re-using the same client across databases shares the
 * Google account, while different clients (e.g. a second account) get their own token.
 *
 * The token is stored unencrypted on-device, matching the existing posture for API session tokens.
 */
class GoogleDriveAccountStore(
    private val localSettings: LocalSettings,
) {
    fun refreshToken(clientId: String): String? = localSettings.getString(key(clientId))

    fun saveRefreshToken(
        clientId: String,
        refreshToken: String,
    ) {
        localSettings.putString(key(clientId), refreshToken)
    }

    fun clear(clientId: String) {
        localSettings.remove(key(clientId))
    }

    private fun key(clientId: String) = "$KEY_PREFIX$clientId"

    private companion object {
        const val KEY_PREFIX = "gdrive.refreshToken."
    }
}
