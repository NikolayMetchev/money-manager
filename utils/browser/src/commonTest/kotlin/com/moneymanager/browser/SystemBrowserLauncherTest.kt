package com.moneymanager.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SystemBrowserLauncherTest {
    private val url = "https://accounts.example.test/auth?a=1&b=2"

    @Test
    fun handsTheUrlToTheOpener() {
        val opened = mutableListOf<String>()

        SystemBrowserLauncher {
            opened += it
            true
        }.open(url)

        assertEquals(listOf(url), opened)
    }

    @Test
    fun whenNothingOpensItsMessageCarriesTheUrlToOpenByHand() {
        val failure = assertFailsWith<BrowserLaunchException> { SystemBrowserLauncher { false }.open(url) }

        assertTrue(url in failure.message.orEmpty())
    }

    @Test
    fun theDefaultLauncherIsBuiltOverTheSystemOpener() {
        // Not opened: that would start a real browser. Constructing it must not.
        SystemBrowserLauncher()
    }
}
