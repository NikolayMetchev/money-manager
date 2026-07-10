@file:OptIn(ExperimentalUuidApi::class)

package com.moneymanager.ui.screens

import com.moneymanager.domain.model.ApiRequest
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiResponse
import com.moneymanager.domain.model.ApiResponseId
import com.moneymanager.domain.model.ApiResponseTransaction
import com.moneymanager.domain.model.ApiResponseTransactionId
import com.moneymanager.domain.model.ApiResponseTransactionState
import com.moneymanager.domain.model.ApiSession
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.ApiSessionType
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.JsonPath
import com.moneymanager.domain.model.MonzoCredential
import com.moneymanager.domain.model.MonzoCredentialId
import com.moneymanager.domain.model.apistrategy.ApiImportStrategyId
import com.moneymanager.domain.repository.ApiSessionImportRevision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

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
        val request = request(7)
        val pair = ApiTrafficPair(request = request, response = responseFor(request))
        val highlighted =
            shouldHighlightPair(
                pair = pair,
                responseTransactions = emptyList(),
                highlightRequestId = ApiRequestId(7),
                highlightJsonPath = "$.accounts[0]",
            )
        assertTrue(highlighted)
    }

    @Test
    fun sessionImportedAtCurrentRevision_onlyMatchesCurrentRevision() {
        val imported = setOf(ApiSessionImportRevision(ApiSessionId(1), revisionId = 2))
        assertTrue(sessionImportedAtCurrentRevision(ApiSessionId(1), currentRevision = 2, imported))
        // Imported under an older strategy revision only -> outstanding again.
        assertFalse(sessionImportedAtCurrentRevision(ApiSessionId(1), currentRevision = 3, imported))
        assertFalse(sessionImportedAtCurrentRevision(ApiSessionId(2), currentRevision = 2, imported))
        assertFalse(sessionImportedAtCurrentRevision(ApiSessionId(1), currentRevision = null, imported))
    }

    @Test
    fun filterCredentialsByStrategy_nullSelectsAll() {
        val credentials = listOf(credential(1, strategyA), credential(2, strategyB), credential(3, strategyId = null))
        val strategyByCredential = credentials.associate { it.id to it.strategyId }
        assertEquals(credentials, filterCredentialsByStrategy(credentials, strategyByCredential, selectedStrategyId = null))
    }

    @Test
    fun filterCredentialsByStrategy_keepsOnlyMatchingCredentialsIncludingLegacyFallback() {
        val modern = credential(1, strategyA)
        val other = credential(2, strategyB)
        val legacy = credential(3, strategyId = null)
        val credentials = listOf(modern, other, legacy)
        // The screen resolves legacy (null-strategy) credentials to the fallback strategy before filtering.
        val strategyByCredential =
            mapOf(modern.id to strategyA, other.id to strategyB, legacy.id to strategyA)
        assertEquals(
            listOf(modern, legacy),
            filterCredentialsByStrategy(credentials, strategyByCredential, selectedStrategyId = strategyA),
        )
        assertEquals(
            listOf(other),
            filterCredentialsByStrategy(credentials, strategyByCredential, selectedStrategyId = strategyB),
        )
    }

    @Test
    fun splitSessionsByImportState_partitionsPerCredentialAndCounts() {
        val credentialA = credential(1, strategyA)
        val credentialB = credential(2, strategyB)
        val importedSession = session(10, credentialA.id)
        val oldRevisionSession = session(11, credentialA.id)
        val neverImportedSession = session(12, credentialB.id)
        val split =
            splitSessionsByImportState(
                credentials = listOf(credentialA, credentialB),
                sessionsByCredential =
                    mapOf(
                        credentialA.id to listOf(importedSession, oldRevisionSession),
                        credentialB.id to listOf(neverImportedSession),
                    ),
                currentStrategyRevisionByCredential = mapOf(credentialA.id to 2L, credentialB.id to 1L),
                importedSessionRevisions =
                    setOf(
                        ApiSessionImportRevision(importedSession.id, revisionId = 2),
                        ApiSessionImportRevision(oldRevisionSession.id, revisionId = 1),
                    ),
            )
        assertEquals(listOf(importedSession), split.importedByCredential[credentialA.id])
        assertEquals(listOf(oldRevisionSession), split.outstandingByCredential[credentialA.id])
        assertEquals(listOf(neverImportedSession), split.outstandingByCredential[credentialB.id])
        assertEquals(emptyList(), split.importedByCredential[credentialB.id])
        assertEquals(2, split.outstandingCount)
        assertEquals(1, split.importedCount)
    }

    @Test
    fun splitSessionsByImportState_retainsSessionlessCredentials() {
        val sessionless = credential(1, strategyA)
        val split =
            splitSessionsByImportState(
                credentials = listOf(sessionless),
                sessionsByCredential = emptyMap(),
                currentStrategyRevisionByCredential = mapOf(sessionless.id to 1L),
                importedSessionRevisions = emptySet(),
            )
        assertEquals(emptyList(), split.outstandingByCredential[sessionless.id])
        assertEquals(emptyList(), split.importedByCredential[sessionless.id])
        assertEquals(0, split.outstandingCount)
        assertEquals(0, split.importedCount)
    }

    private val strategyA = ApiImportStrategyId(Uuid.parse("00000000-0000-0000-0000-00000000000a"))
    private val strategyB = ApiImportStrategyId(Uuid.parse("00000000-0000-0000-0000-00000000000b"))

    private fun credential(
        id: Long,
        strategyId: ApiImportStrategyId?,
    ) = MonzoCredential(
        id = MonzoCredentialId(id),
        type = ApiSessionType.MONZO,
        token = "token-$id",
        createdAt = Instant.DISTANT_PAST,
        strategyId = strategyId,
    )

    private fun session(
        id: Long,
        credentialId: MonzoCredentialId,
    ) = ApiSession(
        id = ApiSessionId(id),
        type = ApiSessionType.MONZO,
        token = "token",
        deviceId = DeviceId(1),
        createdAt = Instant.DISTANT_PAST,
        expiresAt = null,
        credentialId = credentialId,
    )

    private fun request(id: Long) =
        ApiRequest(
            id = ApiRequestId(id),
            sessionId = ApiSessionId(1),
            requestedAt = Instant.DISTANT_PAST,
            method = "GET",
            url = "https://example.com",
            headers = emptyList(),
        )

    private fun responseFor(request: ApiRequest) =
        ApiResponse(
            id = ApiResponseId(request.id.id),
            requestId = request.id,
            sessionId = ApiSessionId(1),
            respondedAt = Instant.DISTANT_PAST,
            json = """{"accounts":[{"accountUid":"a"}]}""",
        )
}
