package com.moneymanager.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenInSystemBrowserJvmTest {
    private val url = "https://accounts.example.test/auth?a=1&b=2"

    @Test
    fun usesTheShellOpenerOnWindows() {
        assertEquals(
            listOf(listOf("rundll32", "url.dll,FileProtocolHandler", url)),
            jvmBrowserCommands("Windows 11", hyprland = true, url = url),
        )
    }

    @Test
    fun usesOpenOnMac() {
        assertEquals(listOf(listOf("open", url)), jvmBrowserCommands("Mac OS X", hyprland = true, url = url))
    }

    @Test
    fun opensWithXdgOpenOnLinux() {
        assertEquals(listOf(linuxBrowserOpenCommand(url)), jvmBrowserCommands("Linux", hyprland = false, url = url))
    }

    @Test
    fun underHyprlandTheFocusCommandComesBeforeTheOpener() {
        val commands = jvmBrowserCommands("Linux", hyprland = true, url = url)

        assertEquals(listOf(hyprlandFocusCommand(true), linuxBrowserOpenCommand(url)), commands)
    }

    @Test
    fun reportsWhetherTheCommandStarted() {
        val java =
            ProcessHandle
                .current()
                .info()
                .command()
                .orElseThrow()

        assertTrue(startWithoutWaiting(listOf(java, "-version")))
        assertFalse(startWithoutWaiting(listOf("/no/such/browser-opener")))
    }
}
