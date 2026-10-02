package com.moneymanager.apiimporter

import com.moneymanager.domain.model.apistrategy.ApiPaginationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NextPageTokenTest {
    private val pagination = ApiPaginationConfig(nextCursorField = "result.nextPageCursor")

    private fun body(token: String) = """{"retCode":0,"result":{"list":[],"nextPageCursor":"$token"}}"""

    @Test
    fun `a plain token is sent back exactly as received`() {
        assertEquals("abc%3A1", nextPageToken(body("abc%3A1"), pagination, sentToken = null))
    }

    @Test
    fun `a pre-encoded token is decoded so request encoding reproduces the provider bytes`() {
        val encoded = pagination.copy(nextCursorUrlEncoded = true)

        assertEquals("132766:2,132766:2", nextPageToken(body("132766%3A2%2C132766%3A2"), encoded, sentToken = null))
    }

    @Test
    fun `a blank, absent or echoed token ends the walk`() {
        val encoded = pagination.copy(nextCursorUrlEncoded = true)

        assertNull(nextPageToken(body(""), encoded, sentToken = null))
        assertNull(nextPageToken("""{"result":{"list":[]}}""", encoded, sentToken = null))
        // The echo guard compares the decoded token, i.e. what was actually sent.
        assertNull(nextPageToken(body("1%3A2"), encoded, sentToken = "1:2"))
    }
}
