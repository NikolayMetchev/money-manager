package com.moneymanager.ui.foundation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

// The browser keeps the credential vault in localStorage, not in a file a person could choose.
actual class CredentialFileLocationPickerLauncher {
    actual val isSupported: Boolean = false

    actual fun launch(
        mode: CredentialFilePickerMode,
        initialPath: String?,
    ) = Unit
}

@Suppress("UnusedParameter")
@Composable
actual fun rememberCredentialFileLocationPicker(onResult: (String?) -> Unit): CredentialFileLocationPickerLauncher =
    remember { CredentialFileLocationPickerLauncher() }
