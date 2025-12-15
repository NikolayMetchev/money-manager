@file:Suppress("FunctionName")

package com.moneymanager.compose.scrollbar

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun VerticalScrollbarForLazyList(
    lazyListState: LazyListState,
    modifier: Modifier,
) {
    // No-op on Android - touch scrolling is native
}

@Composable
actual fun VerticalScrollbarForScrollState(
    scrollState: ScrollState,
    modifier: Modifier,
) {
    // No-op on Android - touch scrolling is native
}

@Composable
actual fun HorizontalScrollbarForScrollState(
    scrollState: ScrollState,
    modifier: Modifier,
) {
    // No-op on Android - touch scrolling is native
}
