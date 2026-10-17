package com.moneymanager.tui

import androidx.compose.runtime.Composable
import com.moneymanager.credentialvault.CredentialVault
import com.moneymanager.domain.model.AccountId
import com.moneymanager.session.DatabaseSession

/** A key and what it does, as the help overlay lists it. */
data class KeyBinding(
    val keys: String,
    val description: String,
)

/**
 * One tab of the TUI. [content] fills the body below the tab bar and sees the open database; it handles its own
 * keys with `Modifier.onKeyEvent`, which run before the shell's global ones, and lists them in [bindings] for
 * the help overlay.
 */
class TuiScreen(
    val title: String,
    val bindings: List<KeyBinding> = emptyList(),
    val content: @Composable (TuiScreenScope) -> Unit,
)

/** What a screen can use. */
class TuiScreenScope(
    val session: DatabaseSession,
    /** The open database's imports and background tasks. */
    val work: SessionWork,
    val prompter: DialogPrompter,
    val width: Int,
    /** The body's rows, between the tab bar and the footer. */
    val height: Int,
    /** The account whose transactions are being browsed, once one has been opened. */
    val browsedAccount: AccountId?,
    /** Shows an account's transactions, wherever the shell keeps them. */
    val openAccount: (AccountId) -> Unit,
    /** The open database's cloud sync and the app's lifecycle actions on it. */
    val controller: TuiController,
    val vault: CredentialVault,
)
