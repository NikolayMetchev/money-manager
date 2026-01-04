package com.moneymanager.ui.navigation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Manages navigation history with back/forward stack support.
 */
@Stable
class NavigationHistory(initialScreen: Screen) {
    private val backStack = mutableListOf<Screen>()
    private val forwardStack = mutableListOf<Screen>()

    var currentScreen by mutableStateOf(initialScreen)
        private set

    val canGoBack: Boolean
        get() = backStack.isNotEmpty()

    val canGoForward: Boolean
        get() = forwardStack.isNotEmpty()

    /**
     * Navigate to a new screen, adding current screen to back stack.
     */
    fun navigateTo(screen: Screen) {
        // Don't navigate if it's the same screen
        if (currentScreen == screen) return

        // Add current screen to back stack
        backStack.add(currentScreen)

        // Clear forward stack when navigating to a new screen
        forwardStack.clear()

        currentScreen = screen
    }

    /**
     * Navigate back to the previous screen.
     * @return true if navigation occurred, false if already at the start
     */
    fun navigateBack(): Boolean {
        if (!canGoBack) return false

        // Move current screen to forward stack
        forwardStack.add(currentScreen)

        // Pop from back stack
        currentScreen = backStack.removeLast()

        return true
    }

    /**
     * Navigate forward to the next screen.
     * @return true if navigation occurred, false if already at the end
     */
    fun navigateForward(): Boolean {
        if (!canGoForward) return false

        // Move current screen to back stack
        backStack.add(currentScreen)

        // Pop from forward stack
        currentScreen = forwardStack.removeLast()

        return true
    }

    /**
     * Replace the current screen without adding to history.
     * Useful for updating screen parameters (e.g., scrollToTransferId).
     */
    fun replaceCurrentScreen(screen: Screen) {
        currentScreen = screen
    }
}
