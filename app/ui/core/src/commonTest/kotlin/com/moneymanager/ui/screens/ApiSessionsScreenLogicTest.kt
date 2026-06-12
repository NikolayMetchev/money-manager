package com.moneymanager.ui.screens

import com.moneymanager.domain.model.ApiRequest
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiResponse
import com.moneymanager.domain.model.ApiResponseId
import com.moneymanager.domain.model.ApiResponseTransaction
import com.moneymanager.domain.model.ApiResponseTransactionId
import com.moneymanager.domain.model.ApiResponseTransactionState
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.JsonPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class ApiSessionsScreenLogicTest {
    @Test
    fun startsWithJsonPath_matchesExactAndNestedPaths() {
        assertTrue("$.transactions[0].counterparty".startsWithJsonPath("$.transactions[0]"))
        assertTrue("$.transactions[0]".startsWithJsonPath("$.transactions[0]"))
        assertFalse("$.transactions[10]".startsWithJsonPath("$.transactions[1]"))
    }

    @Test
    fun parseJsonPathSegments_parsesObjectAndArraySegments() {
        assertEquals(listOf("transactions", "2", "counterparty"), parseJsonPathSegments("$.transactions[2].counterparty"))
        assertEquals(listOf("accounts", "0"), parseJsonPathSegments("$.accounts[0]"))
    }

    @Test
    fun isHighlightTarget_requiresNonNullEmptySegments() {
        assertFalse(isHighlightTarget(null))
        assertFalse(isHighlightTarget(listOf("transactions")))
        assertTrue(isHighlightTarget(emptyList()))
    }

    @Test
    fun shouldHighlightPair_doesNotFallbackToRequestWhenJsonPathProvidedButUnmatched() {
        val pair = ApiTrafficPair(request = request(42), response = null)
        val highlighted =
            shouldHighlightPair(
                pair = pair,
                responseTransactions = emptyList(),
                highlightRequestId = ApiRequestId(42),
                highlightJsonPath = "$.transactions[0]",
            )
        assertFalse(highlighted)
    }

    @Test
    fun shouldHighlightPair_allowsRequestFallbackWhenJsonPathMissing() {
        val pair = ApiTrafficPair(request = request(42), response = null)
        val highlighted =
            shouldHighlightPair(
                pair = pair,
                responseTransactions = emptyList(),
                highlightRequestId = ApiRequestId(42),
                highlightJsonPath = null,
            )
        assertTrue(highlighted)
    }

    @Test
    fun shouldHighlightPair_matchesByJsonPathPrefix() {
        val requestId = 43L
        val pair = ApiTrafficPair(request = request(requestId), response = null)
        val highlighted =
            shouldHighlightPair(
                pair = pair,
                responseTransactions =
                    listOf(
                        ApiResponseTransaction(
                            id = ApiResponseTransactionId(1),
                            responseId = ApiResponseId(9),
                            jsonPath = JsonPath("$.transactions[0]"),
                            state = ApiResponseTransactionState.IMPORTED,
                            transactionId = null,
                            errorMessage = null,
                        ),
                    ),
                highlightRequestId = ApiRequestId(requestId),
                highlightJsonPath = "$.transactions[0].counterparty.name",
            )
        assertTrue(highlighted)
    }

    @Test
    fun shouldHighlightPair_highlightsRequestWithResponseEvenWhenJsonPathHasNoTransactions() {
        // An account origin like "$.accounts[0]" targets the accounts response, which has no parsed
        // response transactions. The matched request id plus a present response must still highlight
        // the pair so the JSON tree can expand the node.
        val pair = ApiTrafficPair(request = request(7), response = response(7))
        val highlighted =
            shouldHighlightPair(
                pair = pair,
                responseTransactions = emptyList(),
                highlightRequestId = ApiRequestId(7),
                highlightJsonPath = "$.accounts[0]",
            )
        assertTrue(highlighted)
    }

    private fun request(id: Long) =
        ApiRequest(
            id = ApiRequestId(id),
            sessionId = ApiSessionId(1),
            requestedAt = Instant.DISTANT_PAST,
            method = "GET",
            url = "https://example.com",
            headers = emptyList(),
        )

    private fun response(requestId: Long) =
        ApiResponse(
            id = ApiResponseId(requestId),
            requestId = ApiRequestId(requestId),
            sessionId = ApiSessionId(1),
            respondedAt = Instant.DISTANT_PAST,
            json = """{"accounts":[{"accountUid":"a"}]}""",
        )
}
