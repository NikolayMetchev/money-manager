package com.moneymanager.ui.foundation

import androidx.compose.runtime.Composable

/** Whether the picker should choose an existing credential file or a location for a new one. */
enum class CredentialFilePickerMode { OPEN_EXISTING, CHOOSE_NEW }

/** Platform-specific launcher for choosing where the credential vault file lives. */
expect class CredentialFileLocationPickerLauncher {
    /** False where the platform offers no file dialog; the default location is then the only choice. */
    val isSupported: Boolean

    fun launch(
        mode: CredentialFilePickerMode,
        initialPath: String?,
    )
}

/** Remembers a launcher whose [onResult] receives the chosen absolute path, or null if cancelled. */
@Composable
expect fun rememberCredentialFileLocationPicker(onResult: (String?) -> Unit): CredentialFileLocationPickerLauncher
