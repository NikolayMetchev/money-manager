package com.moneymanager.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

class ProgressModeTest {
    private fun mode(
        quiet: Boolean = false,
        tty: Boolean = true,
        env: Map<String, String> = emptyMap(),
        virtualTerminal: Boolean = true,
    ) = ProgressMode.of(quiet, tty, env::get) { virtualTerminal }

    @Test
    fun aTerminalGetsLiveBars() = assertEquals(ProgressMode.LIVE, mode(env = mapOf("TERM" to "xterm-256color")))

    @Test
    fun quietShowsNothingEvenOnATerminal() = assertEquals(ProgressMode.OFF, mode(quiet = true))

    @Test
    fun aRedirectedStderrGetsPlainLines() = assertEquals(ProgressMode.PLAIN, mode(tty = false))

    @Test
    fun noColorGetsPlainLines() = assertEquals(ProgressMode.PLAIN, mode(env = mapOf("NO_COLOR" to "1")))

    @Test
    fun anEmptyNoColorIsIgnored() = assertEquals(ProgressMode.LIVE, mode(env = mapOf("NO_COLOR" to "")))

    @Test
    fun aDumbTerminalGetsPlainLines() = assertEquals(ProgressMode.PLAIN, mode(env = mapOf("TERM" to "dumb")))

    @Test
    fun aConsoleWithoutEscapeSequencesGetsPlainLines() = assertEquals(ProgressMode.PLAIN, mode(virtualTerminal = false))
}
