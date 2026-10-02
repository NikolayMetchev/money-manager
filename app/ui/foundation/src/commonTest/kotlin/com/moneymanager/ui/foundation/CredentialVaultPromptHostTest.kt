@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.moneymanager.ui.foundation

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.moneymanager.credentialvault.CredentialVault
import com.moneymanager.credentialvault.CredentialVaultLockedException
import com.moneymanager.credentialvault.StoredApiCredential
import com.moneymanager.credentialvault.VaultState
import com.moneymanager.credentialvault.bundleOrNull
import com.moneymanager.credentialvault.testing.TEST_VAULT_PASSWORD
import com.moneymanager.credentialvault.testing.inMemoryCredentialVault
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CredentialVaultPromptHostTest {
    private val monzo = StoredApiCredential(strategyName = "Monzo", token = "monzo-token", createdAtEpochMillis = 1)
    private lateinit var lockedVault: CredentialVault
    private lateinit var emptyVault: CredentialVault

    @BeforeTest
    fun setup() =
        runTest {
            lockedVault = inMemoryCredentialVault()
            lockedVault.create(lockedVault.defaultPath()!!, TEST_VAULT_PASSWORD)
            lockedVault.update("setup") { it.withApiCredential(monzo) }
            lockedVault.lock()
            emptyVault = inMemoryCredentialVault()
        }

    /** Hosts the prompt plus a button that asks [vault] for secrets, recording how that request ended. */
    private fun ComposeUiTest.showPromptFor(
        vault: CredentialVault,
        outcome: (Result<Unit>) -> Unit,
    ) {
        setContent {
            val scope = rememberCoroutineScope()
            CredentialVaultPromptHost(vault)
            Button(onClick = { scope.launch { outcome(runCatching { vault.requireUnlocked("Download Monzo") }.map { }) } }) {
                Text("Need secrets")
            }
        }
        onNodeWithText("Need secrets").performClick()
        waitUntil { onAllNodesWithText("Download Monzo").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeUiTest.typePassword(
        label: String,
        value: String,
    ) {
        val field = onNode(hasText(label).and(hasSetTextAction()))
        field.performTextClearance()
        field.performTextInput(value)
    }

    @Test
    fun a_locked_vault_prompts_for_the_password_and_rejects_a_wrong_one() {
        runMoneyManagerComposeUiTest {
            var outcome: Result<Unit>? = null
            showPromptFor(lockedVault) { outcome = it }

            typePassword("Password", "wrong")
            onNodeWithText("Unlock").performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Wrong password", substring = true).fetchSemanticsNodes().isNotEmpty() }

            typePassword("Password", TEST_VAULT_PASSWORD)
            onNodeWithText("Unlock").performClick()
            waitUntil(timeoutMillis = 10_000) { outcome != null }
            waitForIdle()

            assertEquals(true, outcome?.isSuccess)
            assertEquals(monzo, lockedVault.bundleOrNull()?.apiCredential("Monzo"))
        }
    }

    @Test
    fun cancelling_the_prompt_fails_the_request_and_leaves_the_vault_locked() {
        runMoneyManagerComposeUiTest {
            var outcome: Result<Unit>? = null
            showPromptFor(lockedVault) { outcome = it }

            onNodeWithText("Cancel").performClick()
            waitUntil { outcome != null }
            waitForIdle()

            assertIs<CredentialVaultLockedException>(outcome?.exceptionOrNull())
            assertIs<VaultState.Locked>(lockedVault.state.value)
        }
    }

    @Test
    fun without_a_file_the_prompt_creates_one_at_the_default_location() {
        runMoneyManagerComposeUiTest {
            var outcome: Result<Unit>? = null
            showPromptFor(emptyVault) { outcome = it }

            onNodeWithText("Create a credential file").assertExists()
            typePassword("Password", "new-password")
            typePassword("Confirm password", "new-password")
            onNodeWithText("Create").performClick()
            waitUntil(timeoutMillis = 10_000) { outcome != null }
            waitForIdle()

            assertEquals(true, outcome?.isSuccess)
            assertEquals(VaultState.Unlocked(emptyVault.defaultPath()!!, emptyVault.bundleOrNull()!!), emptyVault.state.value)
        }
    }
}
