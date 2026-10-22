package com.moneymanager.remotestorage.googledrive

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GrantedGoogleTokenTest {
    private val token = GrantedGoogleToken("token", expiresAtMillis = 1_000_000, grantedScopes = "openid  $DRIVE_FILE_SCOPE")

    @Test
    fun `a token is usable until a minute before it expires`() {
        assertTrue(token.isUsable(nowMillis = 939_999))
        assertFalse(token.isUsable(nowMillis = 940_000))
    }

    @Test
    fun `a token covers only the scopes Google granted`() {
        assertTrue(token.covers(listOf(DRIVE_FILE_SCOPE)))
        assertTrue(token.covers(emptyList()))
        assertFalse(token.covers(listOf(DRIVE_FILE_SCOPE, DRIVE_READONLY_SCOPE)))
    }
}
