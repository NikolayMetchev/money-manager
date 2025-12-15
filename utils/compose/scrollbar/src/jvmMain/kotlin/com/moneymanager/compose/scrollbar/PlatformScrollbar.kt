@file:Suppress("FunctionName")

package com.moneymanager.compose.scrollbar

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun VerticalScrollbarForLazyList(
    lazyListState: LazyListState,
    modifier: Modifier,
) {
    VerticalScrollbar(
        modifier = modifier,
        adapter = rememberScrollbarAdapter(lazyListState),
    )
}

@Composable
actual fun VerticalScrollbarForScrollState(
    scrollState: ScrollState,
    modifier: Modifier,
) {
    VerticalScrollbar(
        modifier = modifier,
        adapter = rememberScrollbarAdapter(scrollState),
    )
}

@Composable
actual fun HorizontalScrollbarForScrollState(
    scrollState: ScrollState,
    modifier: Modifier,
) {
    HorizontalScrollbar(
        modifier = modifier,
        adapter = rememberScrollbarAdapter(scrollState),
    )
}
