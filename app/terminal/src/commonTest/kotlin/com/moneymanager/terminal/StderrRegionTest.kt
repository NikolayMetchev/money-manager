package com.moneymanager.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

class StderrRegionTest {
    private val written = mutableListOf<String>()
    private val region = StderrRegion { written += it }

    @Test
    fun withNothingLiveEveryWriteIsPlain() {
        region.printAbove("hello\n")
        region.paused { region.printAbove("prompt: ") }
        region.close(null)

        assertEquals(listOf("hello\n", "prompt: "), written)
    }

    @Test
    fun eachFrameReplacesTheLinesTheLastOneDrew() {
        region.draw("a\nb")
        region.draw("c")
        region.draw("d\ne\nf")

        assertEquals(listOf("\u001b[?25la\nb\n", "\u001b[2F\u001b[Jc\n", "\u001b[1F\u001b[Jd\ne\nf\n"), written)
    }

    @Test
    fun aMessageGoesAboveTheRegionWhichIsRedrawnBelowIt() {
        region.draw("bar")
        written.clear()

        region.printAbove("warning: slow")

        assertEquals(listOf("\u001b[1F\u001b[Jwarning: slow\nbar\n"), written)
    }

    @Test
    fun aPromptClearsTheRegionUntilItIsAnswered() {
        region.draw("bar")
        written.clear()

        val answer =
            region.paused {
                region.printAbove("Password: ")
                // Frames drawn while the person types wait for the prompt to finish.
                region.draw("bar 2")
                "secret"
            }

        assertEquals("secret", answer)
        assertEquals(listOf("\u001b[1F\u001b[J\u001b[?25h", "Password: ", "\u001b[?25lbar 2\n"), written)
    }

    @Test
    fun closingLeavesTheSummaryAndShowsTheCursor() {
        region.draw("bar")
        written.clear()

        region.close("✓ done")
        region.printAbove("after\n")

        assertEquals(listOf("\u001b[1F\u001b[J✓ done\n\u001b[?25h", "after\n"), written)
    }

    @Test
    fun anEmptyFrameDrawsNothing() {
        region.draw("")
        region.draw("bar")

        assertEquals(listOf("\u001b[?25l", "bar\n"), written)
    }
}
