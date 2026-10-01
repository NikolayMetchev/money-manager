package com.moneymanager.remotestorage.googledrive

import com.moneymanager.credentialvault.CredentialVault
import com.moneymanager.credentialvault.StoredGoogleAccount
import java.util.concurrent.ConcurrentHashMap

/** A cached OAuth access token and the epoch-millis instant it expires. */
data class StoredAccessToken(
    val token: String,
    val expiresAtMillis: Long,
) {
    override fun toString(): String = "StoredAccessToken(token=<redacted>, expiresAtMillis=$expiresAtMillis)"
}

// Access tokens live for an hour, so they are cached for this process only rather than written to the
// vault; a new run spends one refresh call instead of re-encrypting the vault every hour. Process-wide
// because providers (and so stores) are rebuilt per call.
private val accessTokens = ConcurrentHashMap<String, StoredAccessToken>()

/**
 * Google OAuth tokens per OAuth client id. The long-lived **refresh token** and its granted scopes live
 * in the encrypted [vault] (outside any database, so a wiped or cloud-hydrated database keeps its
 * Google sign-in); reading them may prompt the user to unlock the vault. The short-lived **access
 * token** is cached in memory only.
 *
 * One token set per OAuth client means re-using the same client across databases shares the Google
 * account, while different clients (e.g. a second account) get their own tokens.
 */
class GoogleDriveAccountStore(
    private val vault: CredentialVault,
) {
    suspend fun refreshToken(clientId: String): String? = account(clientId)?.refreshToken

    /** The OAuth scopes most recently granted for [clientId] (empty if never signed in). */
    suspend fun grantedScopes(clientId: String): Set<String> = account(clientId)?.grantedScopes.orEmpty()

    /** Records a completed interactive sign-in: the refresh token and the scopes it was granted for. */
    suspend fun saveSignIn(
        clientId: String,
        refreshToken: String,
        grantedScopes: Set<String>,
    ) {
        vault.update(REASON) { it.withGoogleAccount(StoredGoogleAccount(clientId, refreshToken, grantedScopes)) }
    }

    fun accessToken(clientId: String): StoredAccessToken? = accessTokens[clientId]

    fun saveAccessToken(
        clientId: String,
        token: String,
        expiresAtMillis: Long,
    ) {
        accessTokens[clientId] = StoredAccessToken(token, expiresAtMillis)
    }

    fun clearAccessToken(clientId: String) {
        accessTokens.remove(clientId)
    }

    suspend fun clear(clientId: String) {
        clearAccessToken(clientId)
        vault.update(REASON) { it.withoutGoogleAccount(clientId) }
    }

    private suspend fun account(clientId: String): StoredGoogleAccount? = vault.requireUnlocked(REASON).googleAccount(clientId)

    private companion object {
        const val REASON = "Connect to Google Drive"
    }
}
