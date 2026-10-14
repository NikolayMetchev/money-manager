package com.moneymanager.cli

import com.github.ajalt.mordant.terminal.Terminal
import com.moneymanager.terminal.StderrRegion
import kotlin.test.Test
import kotlin.test.assertEquals

class ConsoleProgressTest {
    private val recorder = recorder(interactive = true, input = listOf("Alice"))
    private val terminal = Terminal(terminalInterface = recorder)
    private val region = StderrRegion { terminal.rawPrint(it, stderr = true) }
    private val console = Console(terminal, { null }, vaultPasswordFromStdin = false, quiet = false, stderr = region)

    @Test
    fun aMessageDuringLiveProgressIsPrintedAboveTheBars() {
        region.draw("bar")

        console.warn("Downloads are slow")

        assertEquals("\u001b[?25lbar\n\u001b[1F\u001b[JDownloads are slow\nbar\n", recorder.stderr())
    }

    @Test
    fun aPromptClearsTheBarsUntilItIsAnswered() {
        region.draw("bar")

        val answer = console.text("Name", default = "Bob")

        assertEquals("Alice", answer)
        assertEquals("\u001b[?25lbar\n\u001b[1F\u001b[J\u001b[?25hName [Bob]: \u001b[?25lbar\n", recorder.stderr())
    }
}
