package com.moneymanager.ui.foundation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import com.moneymanager.localsettings.MemoryLocalSettings
import com.moneymanager.localsettings.UiStateStore
import kotlinx.coroutines.delay
import kotlinx.serialization.KSerializer
import kotlin.time.Duration.Companion.milliseconds

/**
 * Where views remember their filters and selected tabs, provided once at the app root. The default
 * keeps them in memory only, so previews and tests need no host.
 */
val LocalUiStateStore = staticCompositionLocalOf<UiStateStore?> { null }

private val WRITE_DEBOUNCE = 300.milliseconds

/**
 * [remember]ed state that survives navigating away and restarting the app: it starts from what
 * [LocalUiStateStore] holds under [key] (else [default]) and writes every change back, debounced so a
 * search field doesn't rewrite the settings file on each keystroke. Give each view its own [key]
 * (`<screen>.<name>`). A [restore] that returns a different value lets a view drop what no longer
 * applies (ids of deleted rows).
 */
@Composable
fun <T> rememberPersistedState(
    key: String,
    serializer: KSerializer<T>,
    restore: (T) -> T = { it },
    default: () -> T,
): MutableState<T> {
    val store = LocalUiStateStore.current ?: remember { UiStateStore(MemoryLocalSettings()) }
    val state = remember(key, store) { mutableStateOf(store.get(key, serializer)?.let(restore) ?: default()) }
    val value = state.value
    LaunchedEffect(key, store, value) {
        delay(WRITE_DEBOUNCE)
        store.put(key, serializer, value)
    }
    // Leaving within the debounce would otherwise lose the last change.
    val latest by rememberUpdatedState(value)
    DisposableEffect(key, store) { onDispose { store.put(key, serializer, latest) } }
    return state
}
