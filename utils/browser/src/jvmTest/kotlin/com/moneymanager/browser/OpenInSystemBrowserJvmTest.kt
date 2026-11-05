package com.moneymanager.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenInSystemBrowserJvmTest {
    private val url = "https://accounts.example.test/auth?a=1&b=2"

    @Test
    fun usesTheShellOpenerOnWindows() {
        assertEquals(listOf("rundll32", "url.dll,FileProtocolHandler", url), jvmBrowserOpenCommand("Windows 11", false, url))
    }

    @Test
    fun usesOpenOnMac() {
        assertEquals(listOf("open", url), jvmBrowserOpenCommand("Mac OS X", false, url))
    }

    @Test
    fun usesTheLinuxCommandElsewhere() {
        assertEquals(linuxBrowserOpenCommand(false, url), jvmBrowserOpenCommand("Linux", false, url))
        assertEquals(linuxBrowserOpenCommand(true, url), jvmBrowserOpenCommand("Linux", true, url))
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

    @Test
    fun theDesktopBrowserIsTriedFirstAndNoProcessStartsWhenItWorks() {
        val started = mutableListOf<List<String>>()

        assertTrue(
            openOnJvm(url, "Linux", hyprland = false, browse = { true }, start = {
                started += it
                true
            }),
        )

        assertTrue(started.isEmpty())
    }

    @Test
    fun fallsBackToTheOsCommandWhenTheDesktopCannotBrowse() {
        val started = mutableListOf<List<String>>()

        assertTrue(
            openOnJvm(url, "Linux", hyprland = false, browse = { false }, start = {
                started += it
                true
            }),
        )

        assertEquals(listOf(listOf("xdg-open", url)), started)
    }

    @Test
    fun hyprlandSkipsTheDesktopBrowserSoTheFocusStepRuns() {
        val started = mutableListOf<List<String>>()

        assertTrue(
            openOnJvm(url, "Linux", hyprland = true, browse = { error("not tried") }, start = {
                started += it
                true
            }),
        )

        assertEquals("sh", started.single().first())
    }

    @Test
    fun reportsFailureWhenNothingCouldOpenTheBrowser() {
        assertFalse(openOnJvm(url, "Linux", hyprland = false, browse = { false }, start = { false }))
    }
}
