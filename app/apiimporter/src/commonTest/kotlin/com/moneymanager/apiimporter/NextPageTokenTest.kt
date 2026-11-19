package com.moneymanager.apiimporter

import com.moneymanager.domain.model.apistrategy.ApiPaging
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NextPageTokenTest {
    private val pagination = ApiPaging.Token(tokenField = "result.nextPageCursor", param = "cursor")

    private fun body(token: String) = """{"retCode":0,"result":{"list":[],"nextPageCursor":"$token"}}"""

    @Test
    fun `a plain token is sent back exactly as received`() {
        assertEquals(mapOf("cursor" to "abc%3A1"), nextPageParams(body("abc%3A1"), pagination, sentParams = null))
    }

    @Test
    fun `a pre-encoded token is decoded so request encoding reproduces the provider bytes`() {
        val encoded = pagination.copy(urlEncoded = true)

        assertEquals(
            mapOf("cursor" to "132766:2,132766:2"),
            nextPageParams(body("132766%3A2%2C132766%3A2"), encoded, sentParams = null),
        )
    }

    @Test
    fun `a blank or absent or echoed token ends the walk`() {
        val encoded = pagination.copy(urlEncoded = true)

        assertNull(nextPageParams(body(""), encoded, sentParams = null))
        assertNull(nextPageParams("""{"result":{"list":[]}}""", encoded, sentParams = null))
        // The echo guard compares the decoded token, i.e. what was actually sent.
        assertNull(nextPageParams(body("1%3A2"), encoded, sentParams = mapOf("cursor" to "1:2")))
    }
}
