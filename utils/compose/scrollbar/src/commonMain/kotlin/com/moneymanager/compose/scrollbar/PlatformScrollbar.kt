@file:Suppress("FunctionName")

package com.moneymanager.compose.scrollbar

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun VerticalScrollbarForLazyList(
    lazyListState: LazyListState,
    modifier: Modifier,
)

@Composable
expect fun VerticalScrollbarForScrollState(
    scrollState: ScrollState,
    modifier: Modifier,
)

@Composable
expect fun HorizontalScrollbarForScrollState(
    scrollState: ScrollState,
    modifier: Modifier,
)
