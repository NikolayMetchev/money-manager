package com.moneymanager.ui.util

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.material3.ExposedDropdownMenu as MaterialExposedDropdownMenu

@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun ExposedDropdownMenuBoxScope.ExposedDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    MaterialExposedDropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, modifier = modifier, content = content)
}
