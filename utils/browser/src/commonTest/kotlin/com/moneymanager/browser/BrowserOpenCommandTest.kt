package com.moneymanager.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowserOpenCommandTest {
    private val url = "https://accounts.example.test/auth?a=1&b=2"

    @Test
    fun opensWithXdgOpenWithTheUrlAsItsOwnArgument() {
        assertEquals(listOf("xdg-open", url), linuxBrowserOpenCommand(url))
    }

    @Test
    fun noFocusCommandOutsideHyprland() {
        assertNull(hyprlandFocusCommand(hyprland = false))
    }

    @Test
    fun hyprlandFocusesTheBrowsersWindowByItsStartupWmClass() {
        val command = hyprlandFocusCommand(hyprland = true)!!

        assertEquals(listOf("sh", "-c"), command.take(2))
        assertTrue("StartupWMClass" in command[2])
        assertTrue("hyprctl dispatch focuswindow" in command[2])
    }

    @Test
    fun printUrlLauncherHandsTheUrlToItsCallback() {
        val printed = mutableListOf<String>()

        PrintUrlBrowserLauncher(printed::add).open(url)

        assertEquals(1, printed.size)
        assertTrue(url in printed.single())
    }
}
