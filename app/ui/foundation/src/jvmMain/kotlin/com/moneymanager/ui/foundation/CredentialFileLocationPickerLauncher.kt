package com.moneymanager.ui.foundation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.moneymanager.credentialvault.DEFAULT_VAULT_FILE_NAME
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Paths

private const val CREDENTIAL_FILE_EXTENSION = ".credentials"

actual class CredentialFileLocationPickerLauncher(
    private val onResult: (String?) -> Unit,
) {
    actual val isSupported: Boolean = true

    actual fun launch(
        mode: CredentialFilePickerMode,
        initialPath: String?,
    ) {
        onResult(showDialog(mode, initialPath))
    }

    private fun showDialog(
        mode: CredentialFilePickerMode,
        initialPath: String?,
    ): String? {
        val frame = Frame()
        try {
            val isOpen = mode == CredentialFilePickerMode.OPEN_EXISTING
            val dialog =
                FileDialog(
                    frame,
                    if (isOpen) "Open credential file" else "Choose where to keep your credential file",
                    if (isOpen) FileDialog.LOAD else FileDialog.SAVE,
                )
            val initial = initialPath?.let { Paths.get(it).toAbsolutePath() }
            initial?.parent?.let { dialog.directory = it.toString() }
            dialog.file = initial?.fileName?.toString() ?: DEFAULT_VAULT_FILE_NAME
            dialog.setFilenameFilter { _, name -> name.lowercase().endsWith(CREDENTIAL_FILE_EXTENSION) }
            dialog.isVisible = true

            val directory = dialog.directory ?: return null
            val file = dialog.file ?: return null
            val fileName =
                if (!isOpen && !file.lowercase().endsWith(CREDENTIAL_FILE_EXTENSION)) file + CREDENTIAL_FILE_EXTENSION else file
            return Paths.get(directory, fileName).toAbsolutePath().toString()
        } finally {
            frame.dispose()
        }
    }
}

@Composable
actual fun rememberCredentialFileLocationPicker(onResult: (String?) -> Unit): CredentialFileLocationPickerLauncher {
    val scope = rememberCoroutineScope()
    return remember(onResult) {
        CredentialFileLocationPickerLauncher(onResult = { result -> scope.launch { onResult(result) } })
    }
}
