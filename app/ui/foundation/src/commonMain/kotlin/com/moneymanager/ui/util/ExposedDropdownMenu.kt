package com.moneymanager.ui.util

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The menu of an `ExposedDropdownMenuBox`. androidx material3 1.5 turned it from a member of the box scope into an
 * extension that has to be imported, while the multiplatform material3 the desktop and the browser use still has the
 * member, so common code can't import Material's own. A call site imports this one: where the member exists it
 * wins and this is never reached, and on Android this forwards to the extension.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
expect fun ExposedDropdownMenuBoxScope.ExposedDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
)
