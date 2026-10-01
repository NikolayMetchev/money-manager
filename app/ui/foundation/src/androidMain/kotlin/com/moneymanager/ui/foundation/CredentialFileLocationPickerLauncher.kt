package com.moneymanager.ui.foundation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

// Android keeps the credential file in the app's private databases directory: a user-chosen location
// would be a Storage Access Framework document, which the java.nio-based vault can't write.
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
