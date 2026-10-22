package com.moneymanager.ui.foundation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.moneymanager.domain.model.DbLocation

actual class DatabaseLocationPickerLauncher(
    private val setPendingMode: (DatabasePickerMode?) -> Unit,
) {
    actual fun launch(mode: DatabasePickerMode) {
        setPendingMode(mode)
    }
}

@Composable
actual fun rememberDatabaseLocationPicker(onResult: (DbLocation?) -> Unit): DatabaseLocationPickerLauncher {
    val context = LocalContext.current
    var pendingMode by remember { mutableStateOf<DatabasePickerMode?>(null) }
    val launcher = remember { DatabaseLocationPickerLauncher(setPendingMode = { pendingMode = it }) }

    pendingMode?.let { mode ->
        val databaseNames = remember(mode) { context.databaseList().toList() }
        NamedDatabaseDialog(mode, databaseNames) { name ->
            pendingMode = null
            onResult(name?.let(::DbLocation))
        }
    }

    return launcher
}
