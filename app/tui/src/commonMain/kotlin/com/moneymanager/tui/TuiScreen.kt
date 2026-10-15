package com.moneymanager.tui

import androidx.compose.runtime.Composable
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
    val prompter: DialogPrompter,
    val width: Int,
)
