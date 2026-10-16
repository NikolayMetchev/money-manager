package com.moneymanager.tui

import com.moneymanager.credentialvault.CredentialVault

/** The screens `mm tui` shows, in tab order. */
val DEFAULT_SCREENS: List<TuiScreen> = listOf(DatabaseScreen, AccountsScreen, TransactionsScreen)

/**
 * Runs the TUI full screen until the person quits and the open database has closed. Throws
 * [NotATerminalException] without a terminal, and rethrows a failed close once the terminal is restored.
 */
suspend fun runTui(
    controller: TuiController,
    vault: CredentialVault,
    screens: List<TuiScreen> = DEFAULT_SCREENS,
) {
    runFullScreen(content = { TuiApp(controller, screens, vault) }) { controller.run() }
}
