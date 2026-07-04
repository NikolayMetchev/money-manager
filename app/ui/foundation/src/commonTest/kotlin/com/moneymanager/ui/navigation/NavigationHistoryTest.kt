package com.moneymanager.ui.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigationHistoryTest {
    @Test
    fun backStackStartsWithInitialScreenAsCurrent() {
        val history = NavigationHistory(Screen.Accounts())

        assertEquals(listOf<Screen>(Screen.Accounts()), history.backStack.toList())
        assertEquals(Screen.Accounts(), history.currentScreen)
        assertFalse(history.canGoBack)
        assertFalse(history.canGoForward)
    }

    @Test
    fun navigateToPushesScreenAndKeepsCurrentAsLastElement() {
        val history = NavigationHistory(Screen.Accounts())

        history.navigateTo(Screen.Settings)

        assertEquals(listOf(Screen.Accounts(), Screen.Settings), history.backStack.toList())
        assertEquals(Screen.Settings, history.currentScreen)
        assertTrue(history.canGoBack)
    }

    @Test
    fun navigateToSameScreenIsNoOp() {
        val history = NavigationHistory(Screen.Accounts())
        history.navigateTo(Screen.Settings)

        history.navigateTo(Screen.Settings)

        assertEquals(2, history.backStack.size)
    }

    @Test
    fun navigateBackMovesCurrentToForwardStack() {
        val history = NavigationHistory(Screen.Accounts())
        history.navigateTo(Screen.Settings)

        assertTrue(history.navigateBack())

        assertEquals(Screen.Accounts(), history.currentScreen)
        assertEquals(1, history.backStack.size)
        assertTrue(history.canGoForward)
    }

    @Test
    fun navigateBackAtStartReturnsFalseAndKeepsBackStackNonEmpty() {
        val history = NavigationHistory(Screen.Accounts())

        assertFalse(history.navigateBack())

        assertEquals(1, history.backStack.size)
    }

    @Test
    fun navigateForwardRestoresScreenAfterBack() {
        val history = NavigationHistory(Screen.Accounts())
        history.navigateTo(Screen.Settings)
        history.navigateBack()

        assertTrue(history.navigateForward())

        assertEquals(Screen.Settings, history.currentScreen)
        assertFalse(history.canGoForward)
    }

    @Test
    fun navigateForwardWithoutHistoryReturnsFalse() {
        val history = NavigationHistory(Screen.Accounts())

        assertFalse(history.navigateForward())
    }

    @Test
    fun navigateToClearsForwardStack() {
        val history = NavigationHistory(Screen.Accounts())
        history.navigateTo(Screen.Settings)
        history.navigateBack()

        history.navigateTo(Screen.Categories)

        assertFalse(history.canGoForward)
        assertEquals(listOf(Screen.Accounts(), Screen.Categories), history.backStack.toList())
    }

    @Test
    fun replaceCurrentScreenSwapsLastEntryWithoutTouchingHistory() {
        val history = NavigationHistory(Screen.Accounts())
        history.navigateTo(Screen.Imports(ImportTab.DIRECTORIES))

        history.replaceCurrentScreen(Screen.Imports(ImportTab.CSV))

        assertEquals(Screen.Imports(ImportTab.CSV), history.currentScreen)
        assertEquals(2, history.backStack.size)

        history.navigateBack()
        assertEquals(Screen.Accounts(), history.currentScreen)
    }
}
