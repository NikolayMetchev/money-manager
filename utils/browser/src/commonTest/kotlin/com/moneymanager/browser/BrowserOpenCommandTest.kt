package com.moneymanager.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrowserOpenCommandTest {
    private val url = "https://accounts.example.test/auth?a=1&b=2"

    @Test
    fun opensWithXdgOpenOutsideHyprland() {
        assertEquals(listOf("xdg-open", url), linuxBrowserOpenCommand(hyprland = false, url = url))
    }

    @Test
    fun passesTheUrlAsItsOwnArgumentUnderHyprland() {
        val command = linuxBrowserOpenCommand(hyprland = true, url = url)

        assertEquals(listOf("sh", "-c"), command.take(2))
        assertEquals(url, command.last())
        assertTrue("hyprctl dispatch focuswindow" in command[2])
        assertTrue("exec xdg-open" in command[2])
    }

    @Test
    fun printUrlLauncherHandsTheUrlToItsCallback() {
        val printed = mutableListOf<String>()

        PrintUrlBrowserLauncher(printed::add).open(url)

        assertEquals(1, printed.size)
        assertTrue(url in printed.single())
    }
}
