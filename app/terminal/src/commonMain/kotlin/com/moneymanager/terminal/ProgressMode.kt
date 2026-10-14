package com.moneymanager.terminal

/** How a command shows its background tasks on stderr. */
enum class ProgressMode {
    /** Bars redrawn in place. */
    LIVE,

    /** One line per task starting, finishing or reporting a problem: for logs, pipes and dumb terminals. */
    PLAIN,

    /** Nothing at all (`--quiet`). */
    OFF,
    ;

    companion object {
        /**
         * The mode for a run: [OFF] when [quiet]; [PLAIN] when stderr isn't a terminal, `NO_COLOR` is set,
         * `TERM` is `dumb` or the terminal can't take escape sequences ([virtualTerminal]); else [LIVE].
         */
        fun of(
            quiet: Boolean,
            stderrIsTerminal: Boolean,
            env: (String) -> String?,
            virtualTerminal: () -> Boolean = ::enableStderrVirtualTerminal,
        ): ProgressMode =
            when {
                quiet -> OFF
                !stderrIsTerminal -> PLAIN
                !env("NO_COLOR").isNullOrEmpty() -> PLAIN
                env("TERM") == "dumb" -> PLAIN
                !virtualTerminal() -> PLAIN
                else -> LIVE
            }
    }
}
