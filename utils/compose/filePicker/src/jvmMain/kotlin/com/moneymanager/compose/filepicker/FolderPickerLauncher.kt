package com.moneymanager.compose.filepicker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.moneymanager.localsettings.KEY_LAST_DIRECTORY
import kotlinx.coroutines.launch
import javax.swing.JFileChooser

@Composable
actual fun rememberFolderPicker(onResult: (String?) -> Unit): FolderPickerLauncher {
    val scope = rememberCoroutineScope()
    return remember(onResult) {
        FolderPickerLauncher(onResult = { path -> scope.launch { onResult(path) } })
    }
}

actual class FolderPickerLauncher(
    private val onResult: (String?) -> Unit,
) {
    actual fun launch() {
        val chooser =
            JFileChooser().apply {
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                dialogTitle = "Select a folder"
                localSettings.getString(KEY_LAST_DIRECTORY)?.let { currentDirectory = java.io.File(it) }
            }
        val chosen =
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                chooser.selectedFile?.absolutePath
            } else {
                null
            }
        chosen?.let { localSettings.putString(KEY_LAST_DIRECTORY, it) }
        onResult(chosen)
    }
}
