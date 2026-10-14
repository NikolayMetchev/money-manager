package com.moneymanager.terminal

/** Whether stderr is a terminal, where the progress region draws. */
expect fun stderrIsTerminal(): Boolean

/**
 * Makes the console behind stderr interpret escape sequences, returning whether it does. Only Windows
 * consoles need switching on (`ENABLE_VIRTUAL_TERMINAL_PROCESSING`); everywhere else this is true.
 */
expect fun enableStderrVirtualTerminal(): Boolean
