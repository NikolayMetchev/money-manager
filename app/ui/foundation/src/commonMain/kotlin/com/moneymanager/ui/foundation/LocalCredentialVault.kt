package com.moneymanager.ui.foundation

import androidx.compose.runtime.staticCompositionLocalOf
import com.moneymanager.credentialvault.AppliedRemote
import com.moneymanager.credentialvault.CredentialBundle
import com.moneymanager.credentialvault.CredentialVault
import com.moneymanager.credentialvault.CredentialVaultLockedException
import com.moneymanager.credentialvault.UnlockRequest
import com.moneymanager.credentialvault.VaultState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The app-wide [CredentialVault] holding API tokens and Google sign-ins, provided once at the app root.
 * A composition local for the same reason as [LocalImportEngine]: the screens that need a secret are
 * deeply nested. The default is a vault with no database bound, which reports every secret as locked.
 */
val LocalCredentialVault =
    staticCompositionLocalOf<CredentialVault> { UnprovidedCredentialVault }

private object UnprovidedCredentialVault : CredentialVault {
    override val state: StateFlow<VaultState> = MutableStateFlow(VaultState.Unbound)
    override val unlockRequest: StateFlow<UnlockRequest?> = MutableStateFlow(null)

    override fun bindToDatabase(databaseKey: String) = Unit

    override fun defaultPath(): String? = null

    override suspend fun create(
        path: String,
        password: String,
    ) = unprovided()

    override suspend fun unlock(
        password: String,
        path: String?,
    ) = unprovided()

    override suspend fun lock() = Unit

    override suspend fun changePassword(newPassword: String) = unprovided()

    override suspend fun moveTo(newPath: String) = unprovided()

    override suspend fun requireUnlocked(reason: String): CredentialBundle =
        throw CredentialVaultLockedException("No credential vault is available")

    override fun cancelUnlockRequest() = Unit

    override suspend fun update(
        reason: String,
        transform: (CredentialBundle) -> CredentialBundle,
    ) = unprovided()

    override suspend fun encryptedBytes(): ByteArray? = null

    override suspend fun applyRemote(
        bytes: ByteArray,
        password: String?,
        merge: Boolean,
        expectedLocal: ByteArray?,
    ): AppliedRemote = unprovided()

    private fun unprovided(): Nothing = throw CredentialVaultLockedException("No credential vault is available")
}
