package com.moneymanager.ui.foundation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.moneymanager.domain.model.DbLocation

/**
 * The browser's databases, as their [DbLocation]s, for the database picker. The web app provides it from its
 * database manager; without one the picker lists none and can only create.
 */
val LocalBrowserDatabases = staticCompositionLocalOf<suspend () -> List<DbLocation>> { { emptyList() } }

actual class DatabaseLocationPickerLauncher(
    private val setPendingMode: (DatabasePickerMode?) -> Unit,
) {
    actual fun launch(mode: DatabasePickerMode) {
        setPendingMode(mode)
    }
}

// The browser's databases are names in the origin private file system, as Android's are in its databases
// directory: a location is the name under the root, `/<name>`.
@Composable
actual fun rememberDatabaseLocationPicker(onResult: (DbLocation?) -> Unit): DatabaseLocationPickerLauncher {
    val listDatabases = LocalBrowserDatabases.current
    var pendingMode by remember { mutableStateOf<DatabasePickerMode?>(null) }
    val launcher = remember { DatabaseLocationPickerLauncher(setPendingMode = { pendingMode = it }) }

    pendingMode?.let { mode ->
        var databaseNames by remember(mode) { mutableStateOf<List<String>?>(null) }
        LaunchedEffect(mode) { databaseNames = listDatabases().map { it.path.removePrefix("/") } }
        databaseNames?.let { names ->
            NamedDatabaseDialog(mode, names) { name ->
                pendingMode = null
                onResult(name?.let { DbLocation("/$it") })
            }
        }
    }

    return launcher
}
