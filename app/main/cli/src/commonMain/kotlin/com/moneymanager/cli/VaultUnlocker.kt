package com.moneymanager.cli

import co.touchlab.kermit.Logger
import com.moneymanager.archive.ArchiveDecryptionException
import com.moneymanager.credentialvault.CredentialVault
import com.moneymanager.credentialvault.UnlockRequest
import com.moneymanager.credentialvault.VaultState
import com.moneymanager.terminal.Prompter
import kotlinx.coroutines.flow.filterNotNull

private val logger = Logger.withTag("VaultUnlocker")

/**
 * Answers [CredentialVault.unlockRequest] for the life of a command, the CLI's counterpart of the desktop's
 * prompt host: without it, any code that needs a secret waits in `requireUnlocked` forever. A locked vault is
 * unlocked with the supplied password or a prompt; a database with no vault yet is offered one at the default
 * path. Anything else cancels the request, so the caller fails with `CredentialVaultLockedException`.
 *
 * The password the person supplied up front comes from [console]; anything asked goes through [prompter], which
 * is [console] itself on the command line and dialogs in `mm tui`.
 */
internal class VaultUnlocker(
    private val vault: CredentialVault,
    private val console: Console,
    private val prompter: Prompter = console,
) {
    suspend fun answerRequests() {
        vault.unlockRequest.filterNotNull().collect { request ->
            val answered =
                when (val state = vault.state.value) {
                    is VaultState.Locked -> unlock(state.path, request)
                    is VaultState.NoFile -> create(state.path, request)
                    is VaultState.Unlocked, VaultState.Unbound -> false
                }
            if (!answered) vault.cancelUnlockRequest()
        }
    }

    private suspend fun unlock(
        path: String,
        request: UnlockRequest,
    ): Boolean {
        console.suppliedVaultPassword()?.let { supplied ->
            return tryUnlock(supplied, path).also { if (!it) prompter.error("The credential vault password supplied is wrong ($path).") }
        }
        if (!prompter.interactive) {
            prompter.error(
                "${request.reason}: the credential vault is locked. Pass --vault-password-stdin or set ${Console.VAULT_PASSWORD_ENV}.",
            )
            return false
        }
        prompter.error("${request.reason}: unlock the credential vault at $path.")
        repeat(Console.MAX_ATTEMPTS) {
            val password = prompter.secret("Vault password") ?: return false
            if (tryUnlock(password, path)) return true
            prompter.error("Wrong password.")
        }
        return false
    }

    private suspend fun tryUnlock(
        password: String,
        path: String,
    ): Boolean =
        try {
            vault.unlock(password, path)
            true
        } catch (wrong: ArchiveDecryptionException) {
            logger.w { "Credential vault unlock failed: ${wrong.message}" }
            false
        }

    private suspend fun create(
        defaultPath: String,
        request: UnlockRequest,
    ): Boolean {
        if (!prompter.interactive) {
            prompter.error("${request.reason}: this database has no credential vault yet. Run mm in a terminal to create one.")
            return false
        }
        prompter.error("${request.reason}: this database has no credential vault yet.")
        if (!prompter.confirm("Create one", default = true)) return false
        val path = prompter.text("Vault file", defaultPath) ?: return false
        val password = console.suppliedVaultPassword() ?: newPassword() ?: return false
        return runCatching { vault.create(path, password) }
            .onFailure { prompter.error("Couldn't create the credential vault: ${it.message}") }
            .isSuccess
    }

    private suspend fun newPassword(): String? {
        repeat(Console.MAX_ATTEMPTS) {
            val first = prompter.secret("New vault password") ?: return null
            if (prompter.secret("Repeat the password") == first) return first
            prompter.error("The passwords don't match.")
        }
        return null
    }
}
