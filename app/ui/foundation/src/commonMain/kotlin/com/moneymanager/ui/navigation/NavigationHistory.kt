package com.moneymanager.ui.navigation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList

/**
 * Manages navigation history with back/forward stack support.
 *
 * [backStack] always contains the current screen as its last element (it is never empty), so it
 * can be handed directly to Navigation 3's `NavDisplay`.
 */
@Stable
class NavigationHistory(
    initialScreen: Screen,
) {
    /** The navigation back stack; the last element is the current screen. Never empty. */
    val backStack: SnapshotStateList<Screen> = mutableStateListOf(initialScreen)

    private val forwardStack = mutableStateListOf<Screen>()

    val currentScreen: Screen
        get() = backStack.last()

    val canGoBack: Boolean
        get() = backStack.size > 1

    val canGoForward: Boolean
        get() = forwardStack.isNotEmpty()

    /**
     * Navigate to a new screen, adding it to the back stack.
     */
    fun navigateTo(screen: Screen) {
        // Don't navigate if it's the same screen
        if (currentScreen == screen) return

        // Clear forward stack when navigating to a new screen
        forwardStack.clear()

        backStack.add(screen)
    }

    /**
     * Navigate back to the previous screen.
     * @return true if navigation occurred, false if already at the start
     */
    fun navigateBack(): Boolean {
        if (!canGoBack) return false

        // Move current screen to forward stack. removeAt instead of removeLast: on Android
        // compileSdk >= 35, removeLast() binds to the JDK 21 SequencedCollection method, which
        // crashes with NoSuchMethodError on devices below API 35.
        forwardStack.add(backStack.removeAt(backStack.lastIndex))

        return true
    }

    /**
     * Navigate forward to the next screen.
     * @return true if navigation occurred, false if already at the end
     */
    fun navigateForward(): Boolean {
        if (!canGoForward) return false

        backStack.add(forwardStack.removeAt(forwardStack.lastIndex))

        return true
    }

    /**
     * Replace the current screen without adding to history.
     * Useful for updating screen parameters (e.g., scrollToTransferId).
     */
    fun replaceCurrentScreen(screen: Screen) {
        backStack[backStack.lastIndex] = screen
    }
}
