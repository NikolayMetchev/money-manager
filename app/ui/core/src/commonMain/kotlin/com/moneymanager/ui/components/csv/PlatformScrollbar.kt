package com.moneymanager.ui.components.csv

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
expect fun HorizontalScrollbarForScrollState(
    scrollState: ScrollState,
    modifier: Modifier,
)
