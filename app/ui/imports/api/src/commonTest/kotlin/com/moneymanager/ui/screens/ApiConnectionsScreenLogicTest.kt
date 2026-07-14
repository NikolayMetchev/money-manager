@file:OptIn(ExperimentalUuidApi::class, kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.screens

import com.moneymanager.domain.model.apistrategy.ApiAccountMappings
import com.moneymanager.domain.model.apistrategy.ApiAuthType
import com.moneymanager.domain.model.apistrategy.ApiEndpointConfig
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.apistrategy.ApiImportStrategyId
import com.moneymanager.domain.model.apistrategy.ApiTransactionMappings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The connect-and-advance rule: after connecting (or skipping) one API, the checklist opens the next one
 * still worth setting up.
 */
class ApiConnectionsScreenLogicTest {
    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private fun strategy(name: String): ApiImportStrategy =
        ApiImportStrategy(
            id = ApiImportStrategyId(Uuid.random()),
            name = name,
            baseUrl = "https://api.$name.com",
            authType = ApiAuthType.BEARER_TOKEN,
            accountsEndpoint = ApiEndpointConfig(path = "/accounts", responseArrayKey = "accounts"),
            transactionsEndpoint = ApiEndpointConfig(path = "/transactions", responseArrayKey = "transactions"),
            accountMappings = ApiAccountMappings(),
            transactionMappings = ApiTransactionMappings(),
            createdAt = now,
            updatedAt = now,
        )

    private val monzo = strategy("monzo")
    private val starling = strategy("starling")
    private val wise = strategy("wise")
    private val all = listOf(monzo, starling, wise)

    @Test
    fun picksTheNextStrategyAfterTheOneJustConnected() {
        assertEquals(
            starling,
            nextApiToSetUp(all, connectedStrategyIds = setOf(monzo.id), skippedStrategyIds = emptySet(), after = monzo.id),
        )
    }

    @Test
    fun passesOverStrategiesThatAreAlreadyConnected() {
        assertEquals(
            wise,
            nextApiToSetUp(
                all,
                connectedStrategyIds = setOf(monzo.id, starling.id),
                skippedStrategyIds = emptySet(),
                after = monzo.id,
            ),
        )
    }

    @Test
    fun passesOverStrategiesSkippedThisVisit() {
        assertEquals(
            wise,
            nextApiToSetUp(
                all,
                connectedStrategyIds = setOf(monzo.id),
                skippedStrategyIds = setOf(starling.id),
                after = monzo.id,
            ),
        )
    }

    @Test
    fun wrapsAroundToAnEarlierStrategyStillNeedingSetup() {
        // Connected the last one first: the only candidate left is behind it in the list.
        assertEquals(
            monzo,
            nextApiToSetUp(
                all,
                connectedStrategyIds = setOf(wise.id, starling.id),
                skippedStrategyIds = emptySet(),
                after = wise.id,
            ),
        )
    }

    @Test
    fun returnsNullWhenNothingIsLeftToSetUp() {
        assertNull(
            nextApiToSetUp(
                all,
                connectedStrategyIds = setOf(monzo.id, wise.id),
                skippedStrategyIds = setOf(starling.id),
                after = monzo.id,
            ),
        )
    }

    @Test
    fun startsAtTheFirstCandidateWhenNothingHasBeenConnectedYet() {
        assertEquals(
            monzo,
            nextApiToSetUp(all, connectedStrategyIds = emptySet(), skippedStrategyIds = emptySet(), after = null),
        )
    }
}
