package com.moneymanager.ui

import androidx.compose.runtime.Composable
import com.moneymanager.domain.model.DbLocation

/** Whether the database picker should open an existing database or create a new one. */
enum class DatabasePickerMode { OPEN, CREATE }

/** Platform-specific launcher that opens a dialog for choosing a [DbLocation]. */
expect class DatabaseLocationPickerLauncher {
    fun launch(mode: DatabasePickerMode)
}

/**
 * Remembers a launcher that lets the user choose a [DbLocation] to open or create.
 * [onResult] receives the chosen location, or null if the user cancelled.
 */
@Composable
expect fun rememberDatabaseLocationPicker(onResult: (DbLocation?) -> Unit): DatabaseLocationPickerLauncher
