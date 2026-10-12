package com.moneymanager.remotestorage.googledrive

import com.moneymanager.httpclient.platformHttpClient
import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GoogleOAuthTest {
    private val oauth = GoogleOAuth(platformHttpClient())

    @Test
    fun newStateIsSixteenRandomBytesInHex() {
        val state = oauth.newState()
        assertEquals(32, state.length)
        assertTrue(state.all { it in "0123456789abcdef" }, state)
        assertNotEquals(state, oauth.newState())
    }

    @Test
    fun consentUrlCarriesTheLoopbackRequest() {
        val url = Url(oauth.consentUrl("client-id", "http://127.0.0.1:4321", "state-1", listOf(DRIVE_FILE_SCOPE, DRIVE_READONLY_SCOPE)))
        assertEquals("accounts.google.com", url.host)
        assertEquals("client-id", url.parameters["client_id"])
        assertEquals("http://127.0.0.1:4321", url.parameters["redirect_uri"])
        assertEquals("code", url.parameters["response_type"])
        assertEquals("$DRIVE_FILE_SCOPE $DRIVE_READONLY_SCOPE", url.parameters["scope"])
        assertEquals("offline", url.parameters["access_type"])
        assertEquals("state-1", url.parameters["state"])
    }
}
