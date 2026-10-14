package com.moneymanager.cli

import com.github.ajalt.mordant.terminal.TerminalRecorder
import com.moneymanager.credentialvault.CredentialVault
import com.moneymanager.credentialvault.CredentialVaultLockedException
import com.moneymanager.credentialvault.VaultState
import com.moneymanager.credentialvault.testing.TEST_VAULT_PASSWORD
import com.moneymanager.credentialvault.testing.inMemoryCredentialVault
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class VaultUnlockerTest {
    private suspend fun lockedVault(): CredentialVault =
        inMemoryCredentialVault().apply {
            create(requireNotNull(defaultPath()), TEST_VAULT_PASSWORD)
            lock()
        }

    private fun TestScope.answering(
        vault: CredentialVault,
        console: Console,
    ) {
        backgroundScope.launch { VaultUnlocker(vault, console).answerRequests() }
    }

    @Test
    fun unlocksWithThePasswordFromTheEnvironment() =
        runTest {
            val vault = lockedVault()
            answering(vault, console(recorder(), env = mapOf(Console.VAULT_PASSWORD_ENV to TEST_VAULT_PASSWORD)))

            vault.requireUnlocked("test")

            assertIs<VaultState.Unlocked>(vault.state.value)
        }

    @Test
    fun unlocksWithThePasswordFromStdin() =
        runTest {
            val vault = lockedVault()
            answering(vault, console(recorder(input = listOf(TEST_VAULT_PASSWORD)), passwordFromStdin = true))

            vault.requireUnlocked("test")

            assertIs<VaultState.Unlocked>(vault.state.value)
        }

    @Test
    fun aWrongSuppliedPasswordFailsInsteadOfHanging() =
        runTest {
            val vault = lockedVault()
            val recorder = recorder()
            answering(vault, console(recorder, env = mapOf(Console.VAULT_PASSWORD_ENV to "wrong")))

            assertFailsWith<CredentialVaultLockedException> { vault.requireUnlocked("test") }
            assertContains(recorder.stderr(), "password supplied is wrong")
        }

    @Test
    fun withoutAPasswordOrATerminalTheRequestIsCancelledWithAHint() =
        runTest {
            val vault = lockedVault()
            val recorder = recorder()
            answering(vault, console(recorder))

            assertFailsWith<CredentialVaultLockedException> { vault.requireUnlocked("Downloading Monzo") }
            assertContains(recorder.stderr(), "Downloading Monzo: the credential vault is locked")
            assertContains(recorder.stderr(), Console.VAULT_PASSWORD_ENV)
        }

    @Test
    fun promptsAgainAfterAWrongPassword() =
        runTest {
            val vault = lockedVault()
            val recorder = recorder(interactive = true, input = listOf("wrong", TEST_VAULT_PASSWORD))
            answering(vault, console(recorder))

            vault.requireUnlocked("test")

            assertIs<VaultState.Unlocked>(vault.state.value)
            assertContains(recorder.stderr(), "Wrong password.")
        }

    @Test
    fun offersToCreateAVaultAtTheDefaultPath() =
        runTest {
            val vault = inMemoryCredentialVault()
            val defaultPath = requireNotNull(vault.defaultPath())
            answering(vault, console(recorder(interactive = true, input = listOf("y", "", "new-password", "new-password"))))

            vault.requireUnlocked("test")

            assertEquals(defaultPath, (vault.state.value as VaultState.Unlocked).path)
        }

    @Test
    fun createsNothingWithoutATerminal() =
        runTest {
            val vault = inMemoryCredentialVault()
            val recorder: TerminalRecorder = recorder()
            answering(vault, console(recorder, env = mapOf(Console.VAULT_PASSWORD_ENV to TEST_VAULT_PASSWORD)))

            assertFailsWith<CredentialVaultLockedException> { vault.requireUnlocked("test") }
            assertIs<VaultState.NoFile>(vault.state.value)
            assertContains(recorder.stderr(), "no credential vault yet")
        }
}
