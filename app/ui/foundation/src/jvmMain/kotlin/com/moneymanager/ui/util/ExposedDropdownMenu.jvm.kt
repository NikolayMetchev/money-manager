package com.moneymanager.ui.util

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// Resolves to the box scope's own member, which takes precedence over this extension.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun ExposedDropdownMenuBoxScope.ExposedDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    ExposedDropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, modifier = modifier, content = content)
}
