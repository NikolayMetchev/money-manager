package com.moneymanager.remotestorage.googledrive

/**
 * An access token a token-only sign-in granted (Google Identity Services in the browser): no refresh token, so once it
 * lapses the source asks Google for another.
 */
class GrantedGoogleToken(
    val accessToken: String,
    val expiresAtMillis: Long,
    /** The space-separated scopes Google granted, which can be fewer than were asked for. */
    grantedScopes: String,
) {
    private val scopes = grantedScopes.split(' ').filter { it.isNotEmpty() }.toSet()

    /** Usable at [nowMillis], with a margin so it can't lapse between the check and the request. */
    fun isUsable(nowMillis: Long): Boolean = expiresAtMillis > nowMillis + EXPIRY_MARGIN_MS

    fun covers(required: List<String>): Boolean = scopes.containsAll(required)

    private companion object {
        const val EXPIRY_MARGIN_MS = 60_000L
    }
}
