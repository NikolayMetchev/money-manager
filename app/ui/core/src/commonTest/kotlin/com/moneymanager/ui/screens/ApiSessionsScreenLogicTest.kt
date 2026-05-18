package com.moneymanager.ui.screens

import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiResponseId
import com.moneymanager.domain.model.ApiResponseTransaction
import com.moneymanager.domain.model.ApiResponseTransactionId
import com.moneymanager.domain.model.ApiResponseTransactionState
import com.moneymanager.domain.model.JsonPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
        val pair = ApiTrafficPair(request = request(42), response = null)
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
                highlightRequestId = ApiRequestId(42),
                highlightJsonPath = "$.transactions[0].counterparty.name",
            )
        assertTrue(highlighted)
    }

    private fun request(id: Long) =
        com.moneymanager.domain.model.ApiRequest(
            id = ApiRequestId(id),
            sessionId =
                com.moneymanager.domain.model
                    .ApiSessionId(1),
            requestedAt = kotlin.time.Instant.DISTANT_PAST,
            method = "GET",
            url = "https://example.com",
            headers = emptyList(),
        )
}
