package com.moneymanager.ui.credentials

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.moneymanager.credentialvault.CredentialBundle
import com.moneymanager.credentialvault.CredentialVault
import com.moneymanager.credentialvault.StoredApiCredential
import com.moneymanager.credentialvault.bundleOrNull
import com.moneymanager.credentialvault.testing.unlockedCredentialVault
import com.moneymanager.test.database.DbTest
import com.moneymanager.ui.error.ProvideSchemaAwareScope
import com.moneymanager.ui.foundation.LocalCredentialVault
import com.moneymanager.ui.foundation.LocalImportEngine
import com.moneymanager.ui.screens.apistrategy.ApiConnectionsScreen
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * API secrets live in the credential vault, not the database, so a recreated database picks its APIs
 * straight back up: reinstall the strategy and it shows as connected, with no token re-entry.
 */
@OptIn(ExperimentalTestApi::class)
class ApiConnectionsVaultE2ETest : DbTest() {
    override val installBuiltInStrategies: Boolean = true

    private val monzo = StoredApiCredential(strategyName = "Monzo", token = "monzo-token-123456", createdAtEpochMillis = 1)

    private fun connectionRowFor(strategyName: String) =
        runBlocking {
            val strategy =
                repositories.apiImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .single { it.name == strategyName }
            repositories.apiSessionRepository.getAllCredentials().singleOrNull { it.strategyId == strategy.id }
        }

    private fun androidx.compose.ui.test.ComposeUiTest.showConnections(vault: CredentialVault) {
        setContent {
            CompositionLocalProvider(
                LocalImportEngine provides repositories.importEngine,
                LocalCredentialVault provides vault,
            ) {
                ProvideSchemaAwareScope {
                    ApiConnectionsScreen(
                        apiImportStrategyRepository = repositories.apiImportStrategyRepository,
                        apiSessionRepository = repositories.apiSessionRepository,
                    )
                }
            }
        }
    }

    @Test
    fun a_fresh_database_reconnects_apis_from_the_vault() {
        runMoneyManagerComposeUiTest {
            val vault = runBlocking { unlockedCredentialVault(CredentialBundle(apiCredentials = listOf(monzo))) }
            assertNull(connectionRowFor("Monzo"), "a fresh database has no connection rows")

            showConnections(vault)

            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Connected · monz…3456").fetchSemanticsNodes().isNotEmpty() }
            waitUntil(timeoutMillis = 10_000) { connectionRowFor("Monzo") != null }
            waitForIdle()
        }
    }

    @Test
    fun saving_a_token_stores_it_in_the_vault_not_the_database() {
        runMoneyManagerComposeUiTest {
            val vault = runBlocking { unlockedCredentialVault() }
            showConnections(vault)

            // Binance sorts first, so its Connect button is the first one on screen. It is a signed API:
            // an api key plus an api secret.
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Binance").fetchSemanticsNodes().isNotEmpty() }
            onAllNodesWithText("Connect")[0].performClick()
            onNode(hasText("API key").and(hasSetTextAction())).performTextInput("binance-key-abcdef")
            onNode(hasText("API secret").and(hasSetTextAction())).performTextInput("binance-secret")
            onNodeWithText("Save and continue").performClick()

            waitUntil(timeoutMillis = 10_000) { vault.bundleOrNull()?.apiCredential("Binance") != null }
            waitUntil(timeoutMillis = 10_000) { connectionRowFor("Binance") != null }
            waitForIdle()
            val saved = vault.bundleOrNull()?.apiCredential("Binance")
            assertEquals("binance-key-abcdef", saved?.token)
            assertEquals("binance-secret", saved?.apiSecret)
        }
    }
}
