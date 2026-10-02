package com.moneymanager.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import com.moneymanager.credentialvault.CredentialVault
import com.moneymanager.credentialvault.VaultState
import com.moneymanager.remotestorage.sync.CredentialSyncController
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

/** The credential backup controller, or null when this build has no remote storage. Provided at the app root. */
val LocalCredentialSyncController = staticCompositionLocalOf<CredentialSyncController?> { null }

/**
 * Keeps the credential backup current while it is connected: syncs whenever the vault is unlocked and after
 * every change to its secrets. Mounted once at the app root so it runs whichever screen is showing. It never
 * prompts; a sync that needs the user (another password, a dead sign-in) waits on the Credentials card.
 */
@OptIn(FlowPreview::class)
@Composable
fun CredentialBackupSyncHost(
    vault: CredentialVault,
    controller: CredentialSyncController,
) {
    LaunchedEffect(vault, controller) {
        vault.state
            // Nulls (locked) are kept through distinctUntilChanged so that unlocking again re-syncs.
            .map { it as? VaultState.Unlocked }
            .distinctUntilChanged()
            .filterNotNull()
            // Several secrets are often saved in a burst (e.g. an API key and its secret); upload once.
            .debounce(SYNC_DEBOUNCE_MILLIS)
            .collect { controller.syncNow(vault) }
    }
}

private const val SYNC_DEBOUNCE_MILLIS = 2_000L
