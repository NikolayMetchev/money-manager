package com.moneymanager.remotestorage.googledrive

import com.moneymanager.remotestorage.RemoteAuthException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.parseUrlEncodedParameters
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GoogleOAuthTokenTest {
    private val credentials = GoogleDriveCredentials("client-id", "client-secret")
    private val requests = mutableListOf<HttpRequestData>()
    private var status = HttpStatusCode.OK
    private var body = """{"access_token":"at","expires_in":3599,"refresh_token":"rt","scope":"x"}"""
    private val oauth =
        GoogleOAuth(
            HttpClient(
                MockEngine { request ->
                    requests += request
                    respond(body, status)
                },
            ),
        )

    private fun sentForm() =
        (requests.single().body as OutgoingContent.ByteArrayContent).bytes().decodeToString().parseUrlEncodedParameters()

    @Test
    fun exchangeCodeSendsTheAuthorizationCodeGrant() =
        runTest {
            assertEquals(GoogleTokens("at", 3599, "rt"), oauth.exchangeCode(credentials, "the-code", "http://127.0.0.1:1"))

            assertEquals("https://oauth2.googleapis.com/token", requests.single().url.toString())
            val form = sentForm()
            assertEquals("authorization_code", form["grant_type"])
            assertEquals("the-code", form["code"])
            assertEquals("client-secret", form["client_secret"])
            assertEquals("http://127.0.0.1:1", form["redirect_uri"])
        }

    @Test
    fun refreshSendsTheRefreshTokenGrant() =
        runTest {
            body = """{"access_token":"fresh"}"""

            assertEquals(GoogleTokens("fresh", 0, null), oauth.refresh(credentials, "rt"))
            val form = sentForm()
            assertEquals("refresh_token", form["grant_type"])
            assertEquals("rt", form["refresh_token"])
            assertEquals("client-id", form["client_id"])
        }

    @Test
    fun aRejectedRequestCarriesGooglesError() =
        runTest {
            status = HttpStatusCode.BadRequest
            body = """{"error":"invalid_grant"}"""

            val error = assertFailsWith<RemoteAuthException> { oauth.refresh(credentials, "revoked") }
            assertTrue(error.message!!.contains("400") && error.message!!.contains("invalid_grant"), error.message)
        }

    @Test
    fun aResponseWithoutAnAccessTokenIsAnError() =
        runTest {
            body = "{}"

            val error = assertFailsWith<RemoteAuthException> { oauth.exchangeCode(credentials, "c", "r") }
            assertEquals("Google token response had no access token", error.message)
        }
}
